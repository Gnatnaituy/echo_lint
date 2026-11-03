# EchoLint 部署记录

> 本文件收录历次生产部署的详细记录（环境、数据迁移、验证结果、回滚点与遗留问题）。
> 当前系统状态、验收清单与已知限制见 [production-readiness.md](production-readiness.md)。

---

## 部署记录（2026-10-20）

| 项 | 值 |
|---|---|
| 代码版本 | `main` @ `3d6dda9`（P1/P2 全部批次已合并并推送） |
| 服务器 | `root@<server-ip>`，部署目录 `<deploy-dir>` |
| 访问地址 | http://<server-ip>/ （HTTP/80；前端 nginx，后端仅 127.0.0.1:8082） |
| 管理员 | `admin`（密码在服务器 `<deploy-dir>/.env` 的 `APP_ADMIN_PASSWORD`） |
| 数据库 | `gx-mysql` 内 `echolint` 库；Flyway baseline v1；预置 191 词 |
| AI | DeepSeek 已加密落库（ProviderRegistry）；阿里云百炼待配置 `DASHSCOPE_API_KEY` |
| DB 备份 | `<deploy-dir>/backups/echolint-20261020-071045.sql.gz` |
| 镜像回滚 | `echolint-backend:rollback-20261020-071045`、`echolint-frontend:rollback-20261020-071045` |
| 验证 | 登录 200、匿名 API/上传 401、管理接口 200、容器 healthy、日志 0 ERROR、内存稳定（backend ~319MiB/512MiB） |

### 上线前仍需处理（P0/运维项，非 P1/P2 代码范围）

- **转写服务**：使用本机 whisper.cpp；需启动 `tools/whisper-server.sh --daemon` 并配置 `WHISPER_BASE_URL`/`WHISPER_API_KEY`，否则上传录音会转写失败（FAILED）。
  Linux 生产机额外前提（2026-11-03 补齐）：compose backend 已加 `extra_hosts: host.docker.internal:host-gateway`；
  宿主侧必须用 `WHISPER_HOST=0.0.0.0` 启动（脚本默认只绑 127.0.0.1，容器访问不到），且防火墙只放行
  Docker 网桥网段（whisper.cpp 无鉴权）。排障：`docker exec echolint-backend bash -c 'echo > /dev/tcp/host.docker.internal/9900 && echo REACHABLE'`；
  宿主机 `curl -s -o /dev/null -w '%{http_code}\n' -X POST http://127.0.0.1:9900/v1/audio/transcriptions -F file=@sample.wav -F model=whisper-1`。
- **HTTPS/域名**：当前 HTTP 明文、公网 80；建议域名 + 证书 + 反代，并把 `server.servlet.session.cookie.secure` 打开。
- **备份/监控**：本次为手工备份；建议 cron 每日 mysqldump + uploads 卷备份，接入 Prometheus 告警与日志轮转。
- **安全**：`APP_SECRET_KEY` 已配置，变更会导致已存供应商密文不可解密；管理员默认密码请首次登录后立即修改。

---

---

## 部署记录（2026-10-22，MySQL → PostgreSQL 正式切换）

| 项 | 值 |
|---|---|
| 代码版本 | `main` @ `9904bbc`（PostgreSQL/pgvector + RAG + 浅色分层界面） |
| 部署时间 | 2026-10-22 23:00–23:20（Asia/Shanghai），停机约 20 分钟 |
| 服务器 | `root@<server-ip>`，部署目录 `<deploy-dir>` |
| 访问地址 | http://<server-ip>/ （HTTP/80，nginx 1.31；前端 gzip + `/assets/` immutable 缓存） |
| 后端 | 仅 `127.0.0.1:8082`；健康检查 `/actuator/health` = `{"status":"UP"}` |
| 数据库 | PostgreSQL 17.11 + pgvector 0.8.7；容器 `echolint-postgres`，库/用户 `echolint`/`audit`，仅绑 `127.0.0.1:5433`，卷 `echolint_pg-data` |
| 迁移数据 | 13 张表 / 281 行（录音 9、流水线日志 37、审计 12、词库 191、知识 3、语料 8、AI 留痕 7、供应商 2、任务 8、复核 3、管理员 1）；逐行逐微秒校验一致 |
| 上传文件 | 沿用旧卷 `echolint_uploads`，9 条录音详情页音频 200 可播；新增 `echolint_knowledge-docs` 卷 |
| 旧库备份 | `<deploy-dir>/backups/echolint-mysql-20261022-230331.sql.gz`（16K，含 13 张表） |
| 回滚点 | 镜像 `echolint-backend:rollback-20261022-230331`、`echolint-frontend:rollback-20261022-230331`；旧 compose/.env/DEPLOY.md 在 `backups/*.mysql-20261022-230331*` |
| 验证 | 端到端冒烟 18/18：登录/登出、会话、录音列表 9、详情+音频、知识库 3、AI 留痕 7、审计、统计、RAG 设置、供应商列表；匿名 API/上传 401；重启后数据与 Flyway 历史不变；日志无 ERROR |

### 本次部署暴露的问题（已在 `docs/postgres-migration.md` §0 记录）

- **全新空库首次启动 Flyway V3 失败**：`V3__rag_embedding_1024.sql` 在 Hibernate 建表前 `UPDATE`
  业务表 → `relation "knowledge_entries" does not exist`，应用启动即崩。处理：首次启动临时
  `SPRING_FLYWAY_ENABLED=false` 建表，再恢复 Flyway 应用 V3（V1/V2/V3 全部 success）；未改动迁移文件。
- **pgloader 不支持 MySQL 8.4 认证**：QMynd 报 `MYSQL-UNSUPPORTED-AUTHENTICATION`，改用
  `tools/migrate_mysql_to_pg.py`（pymysql + psycopg2，281 行秒级搬完并做行数校验）。

### 2026-10-22 收尾补丁：复检队列分页

- 「人工复检 → 待复检」队列由「一次拉 100 条 + 前端筛选」改为**服务端分页**（10 条/页）：
  `GET /api/recordings` 新增 `unassigned` 参数（`assignedTo is null`），关键词/优先级/指派筛选全部下推服务端；
  队列底部显示「共 N 条」+ 翻页；复核完本页最后一条自动回退上一页；深链 `/review?id=N` 在目标不在当前页时按 id 拉详情。
- 测试：后端 **537** 项、前端 **135** 项（新增 `reviewQueue` 单测）全部通过；部署后接口冒烟通过
  （分页第 0/1 页、未指派 2 条、只看我的 1 条、关键词、优先级、组合筛选）。

### 上线后仍未处理（沿用旧状态，非本次部署范围）

- **转写未配置**：服务器无 whisper 服务，`WHISPER_BASE_URL` 指向不存在的 host.docker.internal:9900，
  上传录音会转写失败（FAILED）；需自建 whisper.cpp 或配置兼容 ASR。
  自建时的 Linux 前提：compose 的 `extra_hosts: host-gateway`（已补）负责让容器解析 host.docker.internal，
  但 whisper-server 默认只监听 127.0.0.1，必须用 `WHISPER_HOST=0.0.0.0` 启动并限制防火墙，否则即使宿主
  起了服务容器仍不可达（原记录归因于「服务不存在」，实际还有监听地址问题）。
- **RAG 未启用**：未配置 `DASHSCOPE_API_KEY`，`APP_RAG_ENABLED=false`；配密钥后可在设置页开启并全量重建索引。
- **HTTPS/域名**：仍为 HTTP 明文公网 80。
- **Flyway 10.10 对 PG 17 仅版本告警**（官方支持到 16），本次迁移正常执行，不影响功能。
- **备份/监控**：仍为手工备份；建议 cron 每日 `pg_dump` + uploads 卷备份。

---

## 部署记录（2026-10-23，前端 i18n 全站补全）

| 项 | 值 |
|---|---|
| 代码版本 | `main` @ `3f9a425`（内容页中英双语全站补全） |
| 部署时间 | 2026-10-23 12:42–12:44（Asia/Shanghai）；**仅前端**，后端/数据库未变，无停机 |
| 服务器 | `root@<server-ip>`，部署目录 `<deploy-dir>` |
| 访问地址 | http://<server-ip>/ |
| 变更范围 | 仅前端：替换 `<deploy-dir>/frontend/dist` → `docker compose build frontend && up -d frontend`；`nginx.conf`、后端、数据库均未改动 |
| 构建产物 | 本机 `npm run build`，入口 `assets/index-CTgObqZo.js`（旧入口 `assets/index-cGre2tOD.js`） |
| 回滚点 | 镜像 `echolint-frontend:rollback-20261023-124240`；旧 dist `backups/frontend-dist-20261023-124240.tgz` |
| 验证 | 线上入口 hash 与本地构建一致；frontend 容器 Up、nginx 无 error；API 同源代理匿名 401、backend healthy；Playwright 对公网入口做 15 条路由 EN 扫描：0 console/page error、0 raw key、中英切换正常（生产中文数据按约定保持原文） |

回滚命令（镜像已含旧 dist，直接回退镜像即可）：

    docker tag echolint-frontend:rollback-20261023-124240 echolint-frontend:deploy
    cd <deploy-dir> && docker compose up -d --force-recreate frontend

---

## 部署记录（2026-11-03，全量升级：审计 P0/P1/P2/P3 修复）

| 项 | 值 |
|---|---|
| 代码版本 | `main` @ `9ff8bb2` + **未提交工作区变更**（2026-11-03 审计修复：P0/P1 12 项 + P2/P3 42 项；含依赖升级与格式化） |
| 部署时间 | 2026-11-03 19:31–19:33（Asia/Shanghai）；后端/前端重建重启，PostgreSQL 未重建（无停机）；后端启动约 15s |
| 服务器 | `root@<server-ip>`，部署目录 `<deploy-dir>` |
| 访问地址 | http://<server-ip>/ |
| 变更范围 | 后端 jar（Spring Boot 3.3.5 → **3.5.16**，JRE21 运行 Java17 产物）、前端 dist（Vite 7 构建，入口 `assets/index-jNH0BYIu.js`）、`frontend/nginx.conf`（新增 5 个安全响应头 + CSP + 上传/音频代理优化）；服务器 compose/Dockerfile/内存限制保持原样 |
| 数据库 | Flyway 由 **v3 → v6**：应用 V4（pg_trgm/表达式索引 + corpus HNSW）、V5（pipeline_tasks 活动任务唯一索引）、V6（retention created_at 索引），执行 126ms；数据无变化（recordings 9 / app_user 1 / audit_logs 34） |
| 备份 | DB：`<deploy-dir>/backups/echolint-pg-20261103-193150.sql.gz`（16K）；配置/产物：`backups/docker-compose.20261103-193150.yml`、`backups/.env.20261103-193150`、`backups/artifacts-20261103-193150.tgz` |
| 回滚点 | 镜像 `echolint-backend:rollback-20261103-193150`、`echolint-frontend:rollback-20261103-193150` |
| 验证 | 容器 backend healthy（restarts=0）、frontend Up；日志 0 ERROR；`/actuator/health` UP、`/v3/api-docs` 200、Prometheus 8 个 `echolint_*` 业务指标；公网 `/` 200、`/login` 200、新入口 JS 200；匿名 API 401；安全响应头齐全（CSP/X-Frame/Referrer/Permissions/nosniff，`server_tokens off`）；管理员登录 200、`/api/auth/me` 200、`/api/users`、`/api/tasks`、录音 AI 留痕（ADMIN）200；错误口令第 5 次起 429；backend 内存约 326MiB/512MiB |
| 注意 | 会话纪元机制使**部署前所有登录会话立即失效**，用户需重新登录；空库首启 Flyway 顺序限制不变（本次为已有库升级，V4–V6 正常应用）；服务器无 whisper，上传录音仍会 FAILED |

回滚方式（应用层）：

    docker tag echolint-backend:rollback-20261103-193150 echolint-backend:deploy
    docker tag echolint-frontend:rollback-20261103-193150 echolint-frontend:deploy
    # 如需还原 nginx.conf/dist/jar，先从 artifacts-20261103-193150.tgz 解包回 <deploy-dir>/frontend、<deploy-dir>/backend
    cd <deploy-dir> && docker compose up -d --force-recreate backend frontend

数据库已应用 V4–V6（均为增量索引/约束，旧 jar 可兼容运行）；如需完全回退数据，使用上面的 pg_dump 备份恢复。

