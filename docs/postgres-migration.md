# MySQL 8 → PostgreSQL 17 + pgvector 迁移手册（Batch 6）

> 本文是 **EchoLint 从 MySQL 8 迁移到 PostgreSQL 17（pgvector 镜像）** 的操作手册：
> 迁移前检查、schema 初始化顺序、pgloader 只搬数据、数据校验、pgvector 校验、回滚到 MySQL 备份、常见坑。
>
> 目标状态（以 Lead 的实现为准）：
>
> | 项 | 值 |
> |---|---|
> | compose 服务 | `postgres`（镜像 `pgvector/pgvector:pg17`） |
> | 容器名 | `echolint-postgres` |
> | 宿主端口 | `127.0.0.1:5433` → 容器 `5432`（只绑回环） |
> | 库 / 用户 | `echolint` / `audit`（密码 `audit123`） |
> | 数据卷 | `pg-data`（实际 Docker volume 名 `echolint_pg-data`） |
> | 容器内 JDBC | `jdbc:postgresql://postgres:5432/echolint` |
> | 本机默认 JDBC | `jdbc:postgresql://localhost:5432/echolint`（连 compose 容器用 `5433`） |
>
> 迁移采用 **「先让新版应用在空 PG 上建 schema，再只搬数据」** 的方式。
> **不要** 让 pgloader 建表：业务表由 Hibernate `ddl-auto: update` 管理，RAG 的
> `knowledge_chunks` 由 Flyway V2 建表，HNSW 索引由 V3 调整为 1024 维。

---

## 0. 实战补充（2026-10-22 生产切换，必读）

> 本节是真实生产迁移（旧栈 `main@3d6dda9` MySQL 8.4 → 新栈 `main@9904bbc` PostgreSQL 17.11）
> 的踩坑记录，修正了第 2 节「先启动一次新版后端让 schema 初始化」在**全新空库**上的缺陷。

### 0.1 全新空库首次启动：V3 会失败，需要先关一次 Flyway

`V3__rag_embedding_1024.sql` 里有对 `knowledge_entries` / `corpus_entries` 的 `UPDATE`，
而 Flyway 在 Hibernate `ddl-auto: update` 之前执行；空库里这两张业务表还不存在，启动直接崩：

    ERROR: relation "knowledge_entries" does not exist
    Migration V3__rag_embedding_1024.sql failed

绕过方式（**不要改已发布的 V3 文件**，否则已有库的 checksum 会不匹配）：

1. 首次启动后端时关掉 Flyway（例如 `docker-compose.override.yml` 里给 backend 加
   `SPRING_FLYWAY_ENABLED: "false"`），让 Hibernate 先把业务表建出来；
2. 停后端、删掉该 override；
3. 正常启动：Flyway 把非空 schema 登记为基线 V1，再依次应用 V2、V3，全部 success。

> 若目标库是从 MySQL 迁过来的「已有表」环境，或此前已跑过 Flyway，则不受此影响。

### 0.2 pgloader 连不上 MySQL 8.4（caching_sha2_password）

pgloader 3.6.7 的 QMynd 客户端不支持 MySQL 8.x 默认认证插件，连接直接报：

    Failed to connect to mysql at "127.0.0.1" (port 3306) as user "echolint":
    Condition QMYND:MYSQL-UNSUPPORTED-AUTHENTICATION was signalled

MySQL 8.4 默认禁用 `mysql_native_password`，不建议为迁移去改服务器认证配置。
改用仓库自带脚本（pymysql 读 + psycopg2 参数化写）：

```bash
# dry-run：只打印表/列映射与源行数，不写库
python3 tools/migrate_mysql_to_pg.py \
  --env-file /opt/echolint/.env --mysql-port 3306 --pg-port 5433

# 正式执行：清空目标业务表 → 搬数据 → setval 重置序列 → 逐表行数校验（不一致则回滚）
python3 tools/migrate_mysql_to_pg.py \
  --env-file /opt/echolint/.env --mysql-port 3306 --pg-port 5433 --apply
```

脚本要点：`bit(1)`/`tinyint(1)` → `boolean`；`datetime` 按墙上时间直写 `timestamp`（无时区换算）；
只搬 MySQL 与 PG 的共有列，因此新版新增的 NOT NULL 列（`knowledge_entries.source_type` /
`index_status` / `chunk_count`）需先给目标列加默认值（见 §0.3）；搬完按
`pg_get_serial_sequence` 重置 identity 序列；最后逐表比对行数，不一致整事务回滚。

### 0.3 迁移结果（本次生产）

- 13 张表 / 281 行：`recordings 9`、`pipeline_logs 37`、`audit_logs 12`、`dictionary_words 191`、
  `knowledge_entries 3`、`corpus_entries 8`、`ai_invocations 7`、`ai_providers 2`、
  `pipeline_tasks 8`、`review_records 3`、`app_user 1`、`api_keys 0`、`app_settings 0`；
- 逐行、逐微秒比对与旧库完全一致（应用启动后新写的 `audit_logs` 与内置 `aliyun` 供应商行除外）；
- `uploads` 卷沿用旧卷，9 条录音详情页音频可播放；`APP_SECRET_KEY` 未变，旧 `ai_providers` 密文可正常解密。

---

## 1. 迁移前检查

### 1.1 版本与插件

```bash
# 旧库版本（预期 8.0.x），顺便确认字符集
docker exec echolint-mysql mysql -uaudit -paudit123 -N -B echolint -e \
  "SELECT VERSION(); SHOW VARIABLES LIKE 'character_set_server'; SHOW VARIABLES LIKE 'collation_server';"

# 新库版本与 pgvector 扩展（预期 PostgreSQL 17、vector 0.8.x）
docker exec echolint-postgres psql -U audit -d echolint -Atc "SHOW server_version;"
docker exec echolint-postgres psql -U audit -d echolint -Atc \
  "SELECT extname, extversion FROM pg_extension WHERE extname='vector';"
```

- `pgvector/pgvector:pg17` 镜像已内置扩展文件；`CREATE EXTENSION vector` 由 Flyway V2 执行，HNSW 维度由 V3 调整。
- 官方镜像的 `POSTGRES_USER=audit` 是超级用户，可直接建扩展；**托管型 PostgreSQL** 需要实例管理员把
  `vector` 加入允许列表（或在部署时预创建扩展），否则 V2 会以
  `permission denied to create extension "vector"` 启动失败。
- 本机没有 `psql` 时，所有 SQL 都可以用 `docker exec echolint-postgres psql -U audit -d echolint` 执行。

### 1.2 备份（没有备份不迁移）

```bash
# 1) 整库逻辑备份（旧 compose 容器名 echolint-mysql；线上旧实例若是 gx-mysql，替换容器名）
mkdir -p backups
docker exec echolint-mysql sh -c \
  'exec mysqldump -uroot -proot123 --single-transaction --quick --no-tablespaces \
        --default-character-set=utf8mb4 --routines --triggers --databases echolint' \
  | gzip > "backups/echolint-mysql-$(date +%Y%m%d-%H%M%S).sql.gz"

# 2) 备份文件可解压、能看到 CREATE TABLE / INSERT（抽检）
gunzip -c backups/echolint-mysql-*.sql.gz | head -40

# 3) 镜像回滚点：给当前后端/前端镜像打标签
docker tag echolint-backend:latest echolint-backend:rollback-$(date +%Y%m%d-%H%M%S)
docker tag echolint-frontend:latest echolint-frontend:rollback-$(date +%Y%m%d-%H%M%S)

# 4) 保留旧 compose 文件（含 mysql 服务定义），回滚时要用
cp docker-compose.yml backups/docker-compose.mysql.yml
```

> 上传的录音文件在 **`echolint_uploads` 卷**里，不在数据库中；本次迁移不动它。
> RAG 文档导入的原始文件在 **`echolint_knowledge-docs` 卷**（容器内 `/app/data/knowledge-docs`），
> 同样不参与数据库迁移；若使用文档导入功能，服务器搬迁时一并保留/迁移。
> 但服务器搬迁/重建时必须一起迁移这些卷，否则详情页的音频会 404（DB 里只存相对路径）。

### 1.3 记录源库行数（迁移后逐表比对）

```bash
docker exec echolint-mysql mysql -uaudit -paudit123 -N -B echolint -e "
SELECT 'recordings', COUNT(*) FROM recordings UNION ALL
SELECT 'pipeline_logs', COUNT(*) FROM pipeline_logs UNION ALL
SELECT 'pipeline_tasks', COUNT(*) FROM pipeline_tasks UNION ALL
SELECT 'review_records', COUNT(*) FROM review_records UNION ALL
SELECT 'ai_invocations', COUNT(*) FROM ai_invocations UNION ALL
SELECT 'ai_providers', COUNT(*) FROM ai_providers UNION ALL
SELECT 'app_settings', COUNT(*) FROM app_settings UNION ALL
SELECT 'app_user', COUNT(*) FROM app_user UNION ALL
SELECT 'audit_logs', COUNT(*) FROM audit_logs UNION ALL
SELECT 'corpus_entries', COUNT(*) FROM corpus_entries UNION ALL
SELECT 'dictionary_words', COUNT(*) FROM dictionary_words UNION ALL
SELECT 'knowledge_entries', COUNT(*) FROM knowledge_entries UNION ALL
SELECT 'api_keys', COUNT(*) FROM api_keys;" | sort > /tmp/mysql-row-counts.txt
```

### 1.4 兼容性速查

| MySQL 8 | PostgreSQL 17 | 说明 |
|---|---|---|
| `MEDIUMTEXT` / `LONGTEXT` | `text` | PG `text` 无 64KB/16MB 上限；实体里的 `columnDefinition="MEDIUMTEXT"` 必须改为 `TEXT`（Lead 负责），否则 ddl-auto 建表失败 |
| `TINYINT(1)` / `BOOLEAN` | `boolean` | 数据迁移用 pgloader 默认规则 `tinyint(1) → boolean`；手写 SQL 必须用 `true/false`，不能写 `1/0` |
| `DATETIME` | `timestamp(6)`（无时区） | 实体用 `LocalDateTime`，应保持「墙上时间」不变；见 §4.3 时间校验 |
| `ENUM(...)` | `varchar` | Hibernate 按枚举名存字符串，pgloader 只搬数据、不建类型 |
| `AUTO_INCREMENT` | `identity` / `serial` | 显式搬运 id 后必须 `setval` 重置序列（§4.4） |
| `JSON`（本项目实际是 `TEXT`） | `text` | 应用自己序列化 JSON，不需要转 `jsonb` |
| 反引号标识符 | 双引号标识符 | 应用 SQL 由 JPA 生成，避免手写带引号/保留字的 SQL |
| `utf8mb4_0900_ai_ci` | 数据库 locale/排序规则 | 中文 `ORDER BY` 结果可能变化，报表/列表排序需抽查 |
| 无 pgvector | `vector` 类型 + HNSW | 由 Flyway V2/V3 建扩展、`knowledge_chunks` 和 HNSW 索引（1024 维） |

### 1.5 迁移窗口

- 建议维护窗口内操作：**停止写入**（Nginx 摘流量或后端停机），避免迁移过程中新数据只落旧库。
- 迁移前让 `pipeline_tasks` 跑完，避免 RUNNING 任务在切换后重复执行。
- FaaS/网关层如果缓存了 8082 的健康检查，切换时同步调整。

---

## 2. Schema 初始化顺序（先建 schema，再搬数据）

顺序不能颠倒：**新版应用先在空 PG 上把表建好，迁移工具只负责灌数据。**

> ⚠️ **全新空库**必须先按 §0.1 关闭一次 Flyway 让 Hibernate 建表，否则第 2 步启动后端会因
> V3 找不到业务表而失败。已有表的环境直接按本步骤即可。

```bash
# 0) 启动 PostgreSQL（只启动数据库，不启动后端）
docker compose up -d postgres

# 1) 确认容器健康、库可连（宿主机连容器用 5433）
docker exec echolint-postgres pg_isready -U audit -d echolint
docker exec echolint-postgres psql -U audit -d echolint -Atc "SELECT current_database(), current_user;"

# 2) 先启动一次新版后端，让 schema 初始化完成，然后停掉
docker compose up -d backend
docker compose logs -f backend     # 等到 Started EchoLintApplication，确认无 ERROR
docker compose stop backend

# 3) 确认初始化结果（此时业务表为空，只有预置词典/语料等种子数据）
docker exec echolint-postgres psql -U audit -d echolint -c "\dt"
docker exec echolint-postgres psql -U audit -d echolint -c \
  "SELECT installed_rank, version, description, success FROM flyway_schema_history ORDER BY installed_rank;"
```

一次启动会完成三件事：

1. **Flyway**：`V1` 基线标记 → `V2__postgres_pgvector_rag.sql` → `V3__rag_embedding_1024.sql`
   - `CREATE EXTENSION IF NOT EXISTS vector;`
   - `knowledge_chunks`（`bigint GENERATED BY DEFAULT AS IDENTITY`）+ 唯一索引 `(entry_id, chunk_index)`
   - HNSW 表达式索引 `((embedding_text::vector(1024)) vector_cosine_ops)`（V2 建表、V3 调整为 1024 维）
   - 旧库升级用的 `ALTER TABLE ... ADD COLUMN IF NOT EXISTS`（新库由 Hibernate 建表，DO 块自动跳过）
2. **Hibernate `ddl-auto: update`**：建全部业务表（`recordings`、`pipeline_logs`、`pipeline_tasks`、
   `review_records`、`ai_invocations`、`ai_providers`、`app_settings`、`app_user`、`audit_logs`、
   `corpus_entries`、`dictionary_words`、`knowledge_entries`、`api_keys`）与索引。
3. **DataSeedRunner**：在 JPA 建表后幂等写入预置词典/语料；`AdminBootstrapService` 在用户表为空时建管理员。

> ⚠️ **不要用 pgloader 建表**：
> - 业务表的列类型、索引、外键以 Hibernate 实体为准，pgloader 根据 MySQL 元数据生成的 DDL 会与实体
>   （尤其是 `MEDIUMTEXT`、枚举、`@Column` 长度、复合索引）不一致；
> - `knowledge_chunks` 和 pgvector 扩展由 Flyway V2/V3 负责，pgloader 建表只会冲突；
> - pgloader 会把 MySQL 的 `flyway_schema_history` 也搬过来，污染目标库的迁移历史。
>
> 因此命令里固定使用 **`WITH data only`**，并在迁移前清空目标业务表（见 §3.2）。

---

## 3. pgloader 数据迁移（data only）

### 3.1 安装与网络前提

```bash
# macOS（本机开发）：直接装在本机，连 127.0.0.1:3307 / 127.0.0.1:5433
brew install pgloader

# Linux 服务器（Docker 方式）：用 host 网络访问宿主回环端口
docker run --rm --network host -v /tmp:/tmp dimitri/pgloader:latest --version
```

> ⚠️ **MySQL 8.4 默认 `caching_sha2_password`，pgloader 会直接连不上**（见 §0.2）。生产切换时
> 改用 `tools/migrate_mysql_to_pg.py`；下面的 pgloader 步骤仅适用于认证插件为
> `mysql_native_password` 的环境。

**注意**：compose 把 MySQL 绑定在 `127.0.0.1:3307`、PG 绑定在 `127.0.0.1:5433`（只绑回环）。
在 macOS 上，Docker 容器里的 pgloader 用 `host.docker.internal:3307` **连不上**只绑回环的端口；
要么把 pgloader 装在本机，要么把它和数据库放进同一个 Docker 网络、直接使用容器名。
下面的命令按「本机安装 pgloader」给出。

### 3.2 清空目标业务表（保留 schema 和 Flyway 历史）

第一次启动后端时 `DataSeedRunner` 已经写入了预置词典/语料；直接搬运会撞唯一键。先清空业务表，
迁移完成后重启后端，种子逻辑会幂等补齐缺失数据。

```bash
docker exec -i echolint-postgres psql -U audit -d echolint -v ON_ERROR_STOP=1 <<'SQL'
SET client_encoding TO 'UTF8';
TRUNCATE TABLE
  recordings, pipeline_logs, pipeline_tasks, review_records, ai_invocations,
  ai_providers, app_settings, app_user, audit_logs,
  corpus_entries, dictionary_words, knowledge_entries, api_keys
RESTART IDENTITY CASCADE;
SQL
```

> 不要 truncate `flyway_schema_history`，也不要 truncate `knowledge_chunks`（新表，本来就空）。

### 3.3 编写 load 文件

保存为 `/tmp/echolint.load`：

```lisp
LOAD DATABASE
     FROM mysql://audit:audit123@127.0.0.1:3307/echolint
     INTO postgresql://audit:audit123@127.0.0.1:5433/echolint

 WITH data only,
      no truncate,
      workers = 4, concurrency = 1

  SET MySQL PARAMETERS
      net_read_timeout  = '120',
      net_write_timeout = '120'

  SET PostgreSQL PARAMETERS
      maintenance_work_mem to '128MB',
      work_mem to '12MB'

 -- 实体使用 LocalDateTime（PG timestamp 无时区），避免 pgloader 默认 datetime → timestamptz
 CAST type datetime to timestamp drop default drop not null using zero-dates-to-null,
      type timestamp to timestamp drop default drop not null using zero-dates-to-null,
      type date to date using zero-dates-to-null

 -- 目标库迁移历史 / RAG 新表不参与搬运
 EXCLUDING TABLE NAMES MATCHING 'flyway_schema_history', 'knowledge_chunks'
;
```

运行：

```bash
# 先 dry-run 检查表清单与 cast，再正式执行
pgloader --dry-run --verbose /tmp/echolint.load
pgloader --verbose /tmp/echolint.load
```

> 不同 pgloader 版本对 `CAST` 段的支持略有差异。若在 `CAST` 处报解析错误，可暂时删掉该段重跑，
> 但必须按 §4.3 校验时间字段是否偏移（data only 只搬数据，CAST 主要影响 datetime 的时区解释）。

- 源端的 `flyway_schema_history`（MySQL 上只有 V1）**不能**搬过来；目标库的 V1/V2/V3 由新版应用自己维护。
- 目标表已存在时，`data only` 只执行 COPY；不要加 `include drop`/`create tables`，它们会删表重建。
- pgloader 的 `reset sequences` 选项在 `data only` 下语义不可靠（且 pgloader 不知道 Hibernate 生成的
  identity 序列绑定关系），**统一在搬完后手工 `setval`**（§4.4）。

---

## 4. 数据校验

### 4.1 行数逐表比对

```bash
docker exec echolint-postgres psql -U audit -d echolint -At -F $'\t' -c "
SELECT 'recordings', COUNT(*) FROM recordings UNION ALL
SELECT 'pipeline_logs', COUNT(*) FROM pipeline_logs UNION ALL
SELECT 'pipeline_tasks', COUNT(*) FROM pipeline_tasks UNION ALL
SELECT 'review_records', COUNT(*) FROM review_records UNION ALL
SELECT 'ai_invocations', COUNT(*) FROM ai_invocations UNION ALL
SELECT 'ai_providers', COUNT(*) FROM ai_providers UNION ALL
SELECT 'app_settings', COUNT(*) FROM app_settings UNION ALL
SELECT 'app_user', COUNT(*) FROM app_user UNION ALL
SELECT 'audit_logs', COUNT(*) FROM audit_logs UNION ALL
SELECT 'corpus_entries', COUNT(*) FROM corpus_entries UNION ALL
SELECT 'dictionary_words', COUNT(*) FROM dictionary_words UNION ALL
SELECT 'knowledge_entries', COUNT(*) FROM knowledge_entries UNION ALL
SELECT 'api_keys', COUNT(*) FROM api_keys;" | sort > /tmp/pg-row-counts.txt

diff -u /tmp/mysql-row-counts.txt /tmp/pg-row-counts.txt && echo "行数一致"
```

> 行数一致只代表 COPY 没漏行；**不能**代表内容正确。还要做下面的抽样与聚合校验。
> 如果 `pipeline_logs`/`audit_logs` 在迁移窗口内有写入，行数可能有正常差异，需在停机状态下比对。

### 4.2 关键表抽样

```bash
# 录音主表：取最近 & 最早的各 5 条，源/目标两边分别执行后对比
docker exec echolint-mysql mysql -uaudit -paudit123 -N -B echolint -e \
  "SELECT id, file_name, status, hit_count, upload_time FROM recordings ORDER BY id DESC LIMIT 5;"
docker exec echolint-postgres psql -U audit -d echolint -c \
  "SELECT id, file_name, status, hit_count, upload_time FROM recordings ORDER BY id DESC LIMIT 5;"

# 大文本完整性：长度 + 是否可解析（transcript / segments_json / ai_result_json）
docker exec echolint-postgres psql -U audit -d echolint -c \
  "SELECT id, length(transcript) AS t_len, length(segments_json) AS seg_len,
          length(ai_result_json) AS ai_len
     FROM recordings WHERE transcript IS NOT NULL ORDER BY id LIMIT 5;"

# 中文没有乱码：抽样里应出现正常中文，而不是 ?/??
docker exec echolint-postgres psql -U audit -d echolint -Atc \
  "SELECT left(content, 40) FROM dictionary_words WHERE word = '傻逼' LIMIT 1;"

# AI 留痕 JSON 非空且字段可解析
docker exec echolint-postgres psql -U audit -d echolint -c \
  "SELECT id, stage, model, length(request_json) AS req_len, length(response_json) AS resp_len
     FROM ai_invocations ORDER BY id DESC LIMIT 5;"

# 用户/供应商/设置：密码哈希、密钥密文、当前复筛模型
docker exec echolint-postgres psql -U audit -d echolint -c \
  "SELECT id, username, role, enabled FROM app_user ORDER BY id;"
docker exec echolint-postgres psql -U audit -d echolint -c \
  "SELECT provider_key, enabled, api_key_masked FROM ai_providers ORDER BY id;"
docker exec echolint-postgres psql -U audit -d echolint -c \
  "SELECT * FROM app_settings ORDER BY id;"
```

- `api_key_cipher` 是 AES-GCM 密文。**迁移前后 `APP_SECRET_KEY` 必须保持一致**，否则旧密文无法解密，
  需要在「系统设置 → AI 供应商」重新录入密钥（或先清空密文再启动）。这一条比行数更重要。
- `app_settings.screen.provider` 引用的供应商 key 必须存在于 `ai_providers`（pgloader 会按表搬）；
  空表情况下新版启动时 `ProviderRegistry` 会用 yml 重新落库内置供应商，不会覆盖库中已有项。

### 4.3 时间字段校验

```bash
# 源/目标分别执行，比较最小值、最大值与同一条记录的 upload_time/call_time
docker exec echolint-mysql mysql -uaudit -paudit123 -N -B echolint -e \
  "SELECT MIN(upload_time), MAX(upload_time), MIN(call_time), MAX(call_time) FROM recordings;"
docker exec echolint-postgres psql -U audit -d echolint -At -F '|' -c \
  "SELECT MIN(upload_time), MAX(upload_time), MIN(call_time), MAX(call_time) FROM recordings;"
```

- 时间**数字必须一致**（都是「墙上时间」）。如果整体偏移 ±8 小时，说明 pgloader 的
  `datetime → timestamptz` 做了时区换算：检查 load 文件的 `CAST ... to timestamp`、
  `SET PostgreSQL PARAMETERS TimeZone to 'Asia/Shanghai'`，回滚重搬；不要带着偏移上线。
- `upload_time` 等列在实体里是 `LocalDateTime`，PG 侧类型是 `timestamp(6)`（无时区）。

### 4.4 序列重置（必做）

显式搬运的 id 不会推进 PostgreSQL 的 identity/sequence。不重置的话，应用自增到 1000+ 时会与旧 id 撞主键
（MySQL 的 `AUTO_INCREMENT` 会随显式插入自动抬高，PG 不会）。

```bash
docker exec -i echolint-postgres psql -U audit -d echolint -v ON_ERROR_STOP=1 <<'SQL'
DO $$
DECLARE
    r record;
    max_id bigint;
BEGIN
    FOR r IN
        SELECT n.nspname AS schema_name,
               c.relname AS table_name,
               a.attname AS column_name,
               pg_get_serial_sequence(format('%I.%I', n.nspname, c.relname), a.attname) AS seq
          FROM pg_class c
          JOIN pg_namespace n ON n.oid = c.relnamespace
          JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum > 0 AND NOT a.attisdropped
         WHERE c.relkind = 'r'
           AND n.nspname = 'public'
           AND a.attname = 'id'
           AND pg_get_serial_sequence(format('%I.%I', n.nspname, c.relname), a.attname) IS NOT NULL
    LOOP
        EXECUTE format('SELECT COALESCE(MAX(%I), 0) FROM %I.%I',
                       r.column_name, r.schema_name, r.table_name) INTO max_id;
        IF max_id > 0 THEN
            PERFORM setval(r.seq, max_id);
        ELSE
            PERFORM setval(r.seq, 1, false);
        END IF;
    END LOOP;
END $$;
SQL
```

验证：每个序列的 `last_value` 应等于对应表 `MAX(id)`，且下一次 `nextval` 不冲突。

```bash
# 逐个表看序列当前值（示例）
docker exec echolint-postgres psql -U audit -d echolint -c \
  "SELECT last_value FROM recordings_id_seq;
   SELECT MAX(id) FROM recordings;"

# 冒烟：插一条再删掉，确认不撞主键（在演练库执行；生产可跳过）
docker exec echolint-postgres psql -U audit -d echolint -c \
  "INSERT INTO dictionary_words (word, category, severity, source, enabled, hit_count, created_at)
   VALUES ('sequence_smoke_test', 'TEST', 'LOW', 'SEED', false, 0, now()) RETURNING id;"
```

> 序列名不一定是 `<table>_id_seq`：Hibernate 6 可能生成 identity 而不是 serial，
> 用 `pg_get_serial_sequence('<table>', 'id')` 查询，不要硬编码。

---

## 5. pgvector 扩展与索引校验

```bash
# 1) 扩展已安装（预期 extversion 0.8.x）
docker exec echolint-postgres psql -U audit -d echolint -c \
  "SELECT extname, extversion FROM pg_extension WHERE extname = 'vector';"

# 2) knowledge_chunks 表结构
docker exec echolint-postgres psql -U audit -d echolint -c "\d knowledge_chunks"

# 3) HNSW 索引存在，且维度/算子类正确（1024 / vector_cosine_ops）
docker exec echolint-postgres psql -U audit -d echolint -c \
  "SELECT indexname, indexdef FROM pg_indexes
    WHERE tablename = 'knowledge_chunks' ORDER BY indexname;"

# 4) 向量运算符可用（返回 0~2 的余弦距离，数值本身不固定）
docker exec echolint-postgres psql -U audit -d echolint -c \
  "SELECT '[1,2,3]'::vector(3) <=> '[1,2,4]'::vector(3) AS cosine_distance,
          '[1,2,3]'::vector(3) <-> '[1,2,4]'::vector(3) AS l2_distance;"

# 5) 已索引向量维度分布（空表正常；有数据时应只有 1024 与 NULL）
docker exec echolint-postgres psql -U audit -d echolint -c \
  "SELECT embedding_dim, COUNT(*) FROM knowledge_chunks GROUP BY embedding_dim;"
```

- HNSW 维度在 V3 里**写死 1024**，必须与 `APP_RAG_EMBEDDING_DIMENSIONS` 一致。
  换 embedding 模型/维度时必须重建索引（完整步骤见 [docs/rag-integration.md](rag-integration.md) §5.4）。
- 知识库语料迁移后 `embedding_text` 均为 NULL（MySQL 没有向量列）。启用 RAG 后需要回填/重建索引：
  启动时 `app.rag.index.startup-backfill=true` 会自动索引未索引条目；
  也可用 `POST /api/settings/rag/reindex`（`{"scope":"knowledge"|"corpus"|"all"}`，ADMIN）触发。
- RAG 总开关默认 `false`：**不配置 embedding 供应商也能完成数据库迁移**，只是不产生检索结果。

---

## 6. 应用层冒烟（迁移后）

```bash
# 1) 启动全新栈（数据库已就绪）
docker compose up -d

# 2) 健康检查
curl http://localhost:8082/actuator/health

# 3) 登录 + 列表 + 详情（cookies.txt 为示例）
curl -c cookies.txt -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"<管理员密码>"}' http://localhost:8082/api/auth/login
curl -b cookies.txt 'http://localhost:8082/api/recordings?page=0&size=5'
curl -b cookies.txt http://localhost:8082/api/recordings/1
curl -b cookies.txt http://localhost:8082/api/knowledge?page=0&size=5

# 4) Flyway 历史：V1/V2/V3 均 success
docker exec echolint-postgres psql -U audit -d echolint -c \
  "SELECT installed_rank, version, description, success FROM flyway_schema_history ORDER BY installed_rank;"

# 5) 日志无 ERROR
docker compose logs backend | grep -i error || echo "no ERROR"
```

建议至少核对：登录、录音列表分页、详情（音频可播）、AI 调用留痕抽屉、知识库列表、报表导出。
RAG 相关接口（`/api/settings/rag` 等）属于 Batch 6 新功能，验收见 `docs/rag-contract.md`。

---

## 7. 回滚到 MySQL

**前提**：§1.2 的 `mysqldump` 备份仍在、旧镜像 tag 仍在、旧 compose 文件仍保留、`echolint_uploads` 卷未删除。

```bash
# 1) 停新栈（保留卷；不要 docker compose down -v）
docker compose down

# 2) 启动旧 MySQL（用备份的旧 compose，或单独起 mysql:8.0 容器并挂回 mysql-data 卷）
docker compose -f backups/docker-compose.mysql.yml up -d mysql

# 3) 恢复整库备份
gunzip -c backups/echolint-mysql-<时间戳>.sql.gz \
  | docker exec -i echolint-mysql mysql -uroot -proot123 --default-character-set=utf8mb4

# 4) 启动旧版本后端/前端（回滚镜像 tag，或 git 切回上一版本）
docker compose -f backups/docker-compose.mysql.yml up -d

# 5) 校验
docker exec echolint-mysql mysql -uaudit -paudit123 -N -B echolint -e "SELECT COUNT(*) FROM recordings;"
curl http://localhost:8082/actuator/health
```

回滚注意事项：

- 新 PG 栈在切换后产生的数据**不会**自动回到 MySQL。若切换后已有真实写入，退出维护窗口前先用
  `pg_dump` 导出、评估反向补数，或明确接受丢失（演示/灰度窗口内通常可接受）。
- 保留 `echolint_pg-data` 卷（不要 `down -v`），用于事后排查；确认不再需要后再人工删除。
- 旧 MySQL 容器的数据卷是 `echolint_mysql-data`（compose 项目名 `echolint`）。若该卷已被删除，
  只能用 §1.2 的 dump 恢复到全新 MySQL 容器。
- 回滚到旧版本后，RAG 相关接口/页面不存在（旧前端镜像），前端也要一起回滚，避免页面 404。
- `echolint_uploads` / `echolint_knowledge-docs` 卷与回滚无关（数据库按表恢复即可）；不要在回滚时删除。
- 回滚后数据库里的 `API Key`、`用户密码`、AI 供应商密文都来自 dump；`APP_SECRET_KEY` 必须与备份时一致。

---

## 8. 常见坑

1. **让 pgloader 建表**：业务表结构以 Hibernate 实体为准；pgloader 生成的 DDL 与 `MEDIUMTEXT`、枚举、
   复合索引、`@Column` 长度不一致，还会把 `flyway_schema_history` 搬过来。始终 `WITH data only`。
2. **`MEDIUMTEXT` 残留**：实体里 `columnDefinition = "MEDIUMTEXT"` 在 PostgreSQL 上不是合法类型，
   新版启动会建表失败。迁移前确认后端已改为 `TEXT`（PG 的 `text` 足够大，不需要 `MEDIUMTEXT`）。
3. **布尔字面量**：手写 SQL 里 `enabled=1`、`sampled=0`、`success=1` 在 PG 全部报错，必须 `true/false`；
   PostgreSQL 的 boolean 输入接受 `'1'`/`'0'` 字符串（COPY 场景），但 `INSERT ... VALUES (1)` 不行。
4. **序列未重置**：最典型的「迁移后偶发主键冲突」。见 §4.4，务必在应用恢复写入前执行。
5. **扩展权限**：托管 PG 不允许普通用户 `CREATE EXTENSION`，Flyway V2 启动失败。先让 DBA 安装
   `vector`（或授予权限），再启动应用；官方 `pgvector/pgvector:pg17` 镜像内 `audit` 是超级用户，无此问题。
6. **时区偏移**：pgloader 默认 `datetime → timestamptz`，而实体是 `LocalDateTime`（`timestamp` 无时区）。
   按 §3.3 显式 `CAST ... to timestamp`，并按 §4.3 对比时间；偏移 8 小时必须重搬。
7. **`APP_SECRET_KEY` 不一致**：`ai_providers.api_key_cipher` 是 AES-GCM 密文，换 key 后无法解密，
   表现为「供应商列表正常但调用报密钥错误」。迁移时沿用同一把 `APP_SECRET_KEY`，或重新录入密钥。
8. **种子数据先写、搬数据撞唯一键**：第一次启动后端会写预置词典/语料。搬运前 `TRUNCATE` 业务表
   （§3.2），搬完重启让种子逻辑幂等补齐。
9. **Docker 网络**：compose 端口只绑 `127.0.0.1`。macOS 上容器内 pgloader 无法用 `host.docker.internal`
   访问只绑回环的宿主端口；要么本机装 pgloader，要么同一 Docker 网络用容器名直连。
10. **大文本写入慢/OOM**：`ai_invocations.request_json`、`recordings.transcript` 较大；给 pgloader
    `work_mem`、`maintenance_work_mem`，不要开太大并发（`concurrency = 1` 更稳）。
11. **JSON 不要转 `jsonb`**：应用把 JSON 存成字符串（`TEXT`），转成 `jsonb` 后 Hibernate 读取会类型不匹配。
12. **排序规则变化**：MySQL `utf8mb4_0900_ai_ci` 与 PG locale 对中文排序不同，报表/搜索排序需抽查。
13. **`flyway_schema_history` 不要搬**：目标库的 V1/V2/V3 是重新登记的；搬运会破坏 Flyway 状态。
14. **上传卷独立**：数据库迁移不涉及 `echolint_uploads`；但服务器迁移/重建时要单独搬卷，否则音频 404。
15. **迁移期间不要开 RAG**：先完成数据与接口验收，再开启 `APP_RAG_ENABLED` 并配置 embedding，
    避免 embedding 调用失败/配额消耗干扰迁移判断。

---

## 9. 迁移记录（演练/上线后填写）

> 本节只记录**实际执行过**的命令与结果；未实测的项目不要勾选。

| 项 | 结果 |
|---|---|
| 迁移日期 | 2026-10-22（服务器 `8.137.123.233`，停机窗口约 20 分钟） |
| MySQL 版本 / 行数快照 | MySQL 8.4.11；13 张表 / 281 行（明细见 §0.3） |
| PostgreSQL / pgvector 版本 | PostgreSQL 17.11 / pgvector 0.8.7 |
| pgloader 版本与耗时 | pgloader 3.6.7 因 `caching_sha2_password` 不可用（§0.2）；改用 `tools/migrate_mysql_to_pg.py`，281 行秒级完成 |
| 行数比对 | 13/13 张表一致；逐行逐微秒内容比对 281/281 行一致 |
| 时间字段比对 | 一致（`timestamp` 无时区偏移，保留 Asia/Shanghai 墙上时间） |
| 序列重置 | 已按 `pg_get_serial_sequence` 重置；重启后应用新增写入正常（审计日志 id 连续） |
| pgvector / HNSW 校验 | `vector` 0.8.7；V3 HNSW `vector(1024)` 表达式索引已建；`knowledge_chunks` 为空（RAG 未启用） |
| 应用冒烟 | 18/18 通过：登录/会话、录音列表 9、详情+音频 200、知识库 3、AI 留痕 7、审计、统计、RAG 设置、供应商列表 |
| 回滚演练 | 未做完整回滚演练；回滚资产已备（mysqldump + rollback 镜像 tag + 旧 compose/.env） |
| 已知偏差 | 新空库首启需先关一次 Flyway（§0.1）；pgloader 不可用改用脚本（§0.2）；Flyway 10.10 对 PG 17 仅版本告警 |
