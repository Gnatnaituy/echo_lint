# EchoLint 部署记录

> 本文件收录历次生产部署的详细记录（环境、数据迁移、验证结果、回滚点与遗留问题），按时间保留历史原貌。
> 2026-10-22 之前的条目为 **MySQL 8** 时代；此后为 PostgreSQL 17 + pgvector。当前状态见 [production-readiness.md](production-readiness.md)。
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

---

## 部署记录（2026-11-03 21:25，用户级菜单绑定）

| 项 | 值 |
|---|---|
| 代码版本 | `main` @ `1feb9eb` + **未提交工作区变更**（用户管理绑定菜单：MenuKey 枚举 / `app_user.menu_keys` / 用户管理 UI / 侧栏与路由菜单门禁） |
| 部署时间 | 2026-11-03 21:25–21:27（Asia/Shanghai）；后端/前端重建重启，PostgreSQL 未重建；backend 启动约 14.5s |
| 服务器 | `root@<server-ip>`，部署目录 `<deploy-dir>` |
| 访问地址 | http://<server-ip>/ |
| 变更范围 | 后端 jar（新增 `MenuKey` / `MenuOptionView` / `GET /api/users/menu-options` / `menuKeys` 字段 / 防锁死 409 / Flyway V7）；前端 dist（Vite 7 构建，入口 `assets/index-DeJkh4zL.js`）；服务器 compose/Dockerfile/nginx.conf/环境变量均未改动 |
| 数据库 | Flyway 由 **v6 → v7**（`V7__user_menu_binding.sql`，38ms）：`app_user` 新增 `menu_keys varchar(512)`（`NULL` = 不限制 = 跟随角色）；数据无变化，原有账号全部默认「全部菜单」，无需回填 |
| 备份 | DB：`<deploy-dir>/backups/echolint-pg-20261103-212514.sql.gz`（18K）；配置/产物：`backups/docker-compose.20261103-212514.yml`、`backups/.env.20261103-212514`、`backups/artifacts-20261103-212514.tgz`（86M，含旧 jar/dist/nginx.conf） |
| 回滚点 | 镜像 `echolint-backend:rollback-20261103-212514`、`echolint-frontend:rollback-20261103-212514` |
| 验证 | 容器 backend healthy（restarts=0）、frontend Up；日志 0 ERROR；公网 `/`、`/login`、`/users` 200，新入口 JS 200 且 hash 与本地构建一致；匿名 `/api/users`、`/api/recordings` 401；管理员登录 200、`/api/auth/me` 的 `menuKeys`=15、`/api/users/menu-options`=15 项（6 项 adminOnly）、`/api/users` 返回 `menuKeys`/`menuRestricted`；安全响应头（CSP/X-Frame/Referrer/nosniff）保持；backend 内存约 341MiB/512MiB |
| 注意 | 后端重启使部署前所有会话失效，用户需重新登录；菜单绑定只收窄前端导航/页面访问，接口权限仍按角色 URL 矩阵；服务器无 whisper 服务，上传录音仍会 FAILED |

回滚方式（应用层）：

    docker tag echolint-backend:rollback-20261103-212514 echolint-backend:deploy
    docker tag echolint-frontend:rollback-20261103-212514 echolint-frontend:deploy
    # 如需还原 jar/dist/nginx.conf，先从 artifacts-20261103-212514.tgz 解包回 <deploy-dir>/backend、<deploy-dir>/frontend
    cd <deploy-dir> && docker compose up -d --force-recreate backend frontend

数据库已应用 V7（仅加列，旧 jar 可兼容运行）；如需完全回退数据，使用上面的 pg_dump 备份恢复。


---

## 部署记录（2026-11-10 16:06，语音转写双 provider + 系统设置页重构）

| 项 | 值 |
|---|---|
| 代码版本 | `main` + **未提交工作区变更**（ASR 双 provider：本地 whisper.cpp + 阿里云百炼；系统设置页拆分为 5 个子页面；新增「语音转写」页） |
| 部署时间 | 2026-11-10 16:05–16:10（Asia/Shanghai）；后端/前端重建重启，PostgreSQL 未重建；backend 启动约 14s、Flyway 已处于 v7 无新迁移 |
| 访问地址 | https://www.ravooo.cn/settings（设置页子路由：`/screening` `/transcription` `/rag` `/notifications` `/runtime`） |
| 变更范围 | **后端**：新增 `com.echolint.asr` 包（`AsrSettingsService` / `LocalWhisperProvider` / `DashScopeAsrProvider` 等），`TranscriptionService` 改为按运行时配置分发；新增 `GET/PUT /api/settings/asr`、`POST /api/settings/asr/test`（仅 ADMIN）；配置落 `app_settings`、API Key 用 `SecretCipher` 加密、只回掩码。**前端**：`SettingsLayout` + 5 个子页 + 语音转写页（provider 切换/配置/连通性测试），`utils/asr.js` 归一化。 |
| 服务器侧 | 新增本地 whisper.cpp：`/opt/whisper.cpp`（OpenBLAS 编译）+ `ggml-small-q5_1`（181MB）+ systemd `whisper-server.service`（绑定 `172.22.0.1:9900`，`MemoryHigh=600M`/`MemoryMax=750M`/`OOMScoreAdjust=1000`/`Nice=10`，开机自启）；compose 给 backend 加 `extra_hosts: host.docker.internal:172.22.0.1` 与 ASR env 兜底（`ASR_TIMEOUT_SECONDS`/`DASHSCOPE_ASR_*`） |
| 转写默认方案 | 阿里云百炼 `qwen-audio-3.1-asr-flash-filetrans`（Key 由设置页写入、AES-GCM 加密存库，`apiKeySource=db`）；本地 whisper.cpp 保留为可选 provider，设置页一键切换、立即生效、重启保持 |
| 验证 | 两家 `/test` 均 ok（local 512ms / dashscope 592ms）；**真实录音端到端**：百炼 12s demo 2.77s 出稿（3 段、句级时间戳），本地 26s demo 18.6s 出稿（5 段）；设置重启后持久化（activeProvider=dashscope）；HTTPS `/settings/*` 全部 200；管理员 `GET /api/settings/asr` 200 且响应不含明文 Key；测试录音已删除 |
| 备份 | `<deploy-dir>/backups/deploy-20261110-160504/`：`app.jar`、`frontend-dist.tgz`、`docker-compose.yml`、`DEPLOY.md` |
| 回滚 | 应用层：还原备份 jar/dist 后 `docker compose up -d --force-recreate backend frontend`；仅切转写方案：在设置页把 provider 切回「本地 whisper.cpp」即可（无需回滚代码） |
| 注意 | 本地 whisper 常驻约 380–440MB（含 mmap 模型页），内存紧张时会被优先 OOM kill，不误伤 postgres/backend；百炼临时上传接口官方标注「100 QPS、勿用于高并发/生产」，结果 JSON 链接 24h、临时文件 48h，量大时应改用自有 OSS；`host.docker.internal` 显式映射到 compose 网关 `172.22.0.1`，若重建网络导致网段变化需同步调整 |

### 2026-11-10 16:22–16:25 修复轮（代码评审后重新部署）

评审发现并修复 1 个 Blocker + 3 个重要问题，随后重新构建部署 backend/frontend（PostgreSQL 未动）：

| 问题 | 修复 |
|---|---|
| 长录音结果 JSON 超过 WebClient 默认 256KB，整条转写失败 | 本地/百炼 WebClient 统一 `maxInMemorySize(32MB)`；新增 JDK `HttpServer` 假服务测试覆盖 >256KB 结果（3000 段） |
| 仅保存百炼 Key 就隐式切云 / 升级后配了 `DASHSCOPE_API_KEY` 自动切云 | 默认 provider 改 `local`；只有显式 `activeProvider` 才切换；未带 activeProvider 的 PUT 保持当前 provider 并显式落库防漂移 |
| 错误消息可能带出百炼临时 OSS 凭证（policy/signature/oss_access_key_id） | 错误只回 HTTP 状态 + `code/message/request_id`（OSS XML 仅白名单字段）或缺失字段名；原始响应只在 DEBUG 脱敏日志；补断言 |
| 双声道降级单路时云端只转 channel 0、另一侧静默丢失 | 降级前用 ffmpeg downmix 成 16k mono 再转写并记录 WARN；补 3 条降级路径单测 |
| 顺手 | `update` 加 `@Transactional`；本地探活 401/403 判「鉴权失败」；未知 `task_status` 快速失败；`SecurityMatrixTest` 补 PUT/POST 用例 |

- 测试：后端 714 → **731 tests 全绿**（净增 17）；前端 205 → **206 tests 全绿**
- 部署：备份 `backups/deploy-fix-20261110-162228/`；重建 backend/frontend 镜像并重启，Flyway 无新迁移
- 复验：`activeProvider=dashscope` 重启后保持；两家 `/test` 均 ok；真实录音端到端（百炼 15s 音频 5 段、本地 12s 音频 15.6s 出稿 1 段）；坏 upstream 错误消息不含凭证/响应体；`/settings/*` 深链 200；后端日志 0 ERROR
- 说明：生产库已显式存 `asr.provider=dashscope`，不受「默认 local」变更影响

### 2026-11-10 16:39–16:42 设置页二次重构（去重合并 + API Key 只存数据库）

| 项 | 值 |
|---|---|
| 变更 | **前端**：`/settings/screening` 由「当前生效 + 切换 + AI 供应商」三块合并为一张主卡片（状态行 + 供应商网格[可选中/编辑/新增/删除] + 模型 + 测试/保存），删除 `ActiveModelCard/ProviderRegistryCard/ProviderTestDialog`；`/settings/transcription` 合并为单卡（状态行 + provider 切换 + 仅选中 provider 配置 + 一组测试/保存）；`/settings/runtime` 移除全部 `*_API_KEY` 示例，改为「API Key 只存数据库」策略说明；RAG/通知合并重复说明块。**后端**：新增 `AsrKeyBootstrapRunner`，ASR 密钥运行时**只读 app_settings**（AES-GCM），环境变量仅首次启动引导一次（已有值不覆盖）；`GET/PUT /api/settings/asr` 删除 `apiKeySource/envHints` 字段。 |
| 测试 | 后端 731 → **735 tests 全绿**（+4：bootstrap 幂等/不覆盖/local 默认值/DB 故障不炸）；前端 206 → **209 tests 全绿**（+3：供应商网格合并）；lint/format/i18n/build 全绿 |
| 部署 | 备份 `backups/deploy-r2-20261110-163947/`；重建 backend/frontend 并重启，Flyway 无新迁移 |
| 密钥落库验证 | 首次启动日志「本地 whisper.cpp 的 API Key 已从环境变量 WHISPER_API_KEY 读取并加密落库（仅首次启动执行）」；`app_settings` 新增 `asr.local.api-key`（密文 44 字符），`asr.dashscope.api-key` 保持原值（updated_at 未变，未覆盖）；**再次重启 backend：无写入日志、两条密钥行时间戳不变** → 运行时确实只读 DB |
| 接口验证 | `GET /api/settings/asr` 字段仅 `activeProvider/timeoutSeconds/local/dashscope`，两 provider 均 `ready=true`、只回掩码（`****ocal` / `****LOOY`），不含 `apiKeySource/envHints`；两家 `/test` 均 ok |
| 端到端 | 百炼 9s demo → `COMPLIANT`、2 段、耗时 3.6s；`/settings/*` 深链 200，新入口 JS `assets/index-CcUZ47MT.js` 可访问，合并卡片与「API Key 只存数据库」文案已进入产物 |
| 说明 | `.env` 中的密钥类变量此后可删除（不会回退到 env）；修改密钥只能在系统设置页操作（加密落库、界面只显示掩码） |

### 2026-11-10 20:00–20:10 业务空间域名路径导致转写连通失败（排查与修复）

| 项 | 值 |
|---|---|
| 现象 | 设置页「测试连接」报 `调用异常: recvAddress(..) failed with error(-104): Connection reset by peer`，随后报 `HTTP 404: 响应为空`；正式转写不受影响（仍是旧地址） |
| 排查 | ① 后端日志确认到 `dashscope.aliyuncs.com:443` 连接被重置（瞬时网络层，重试即恢复）；② 用户把业务空间完整地址 `https://{workspace}.cn-beijing.maas.aliyuncs.com/api/v1` 填进了「服务地址」。后端会再拼 `/api/v1/uploads`，实际请求成 `/api/v1/api/v1/uploads` → 404 空响应。宿主机/容器直连该工作区域名均正常（无 key 返回 401），排除网络与域名问题 |
| 立即修复 | 通过设置接口把 `asr.dashscope.base-url` 修正为 `https://llm-….cn-beijing.maas.aliyuncs.com`（域名根）→ 测试连接 2/2 成功（~73ms），真实转写 18s demo → 4 段、耗时 4.1s |
| 代码加固 | 后端 `AsrSettingsService.normalizeDashScopeBaseUrl()`：保存与读取时把百炼 base-url 统一收敛为 `scheme://authority`（兼容历史库中的完整接口地址）；空响应错误提示补充「若为 HTTP 404，请检查服务地址是否为域名根、不要带 /api/v1」；前端提交前同样归一化 + 提示文案更正。新增单测：归一化 3 种输入 + 404 空响应提示 |
| 验证 | 后端 737 tests、前端 209 tests 全绿；部署后 PUT 带 `/api/v1` → GET 返回域名根；测试连接 + 端到端转写均正常 |

### 2026-11-10 20:15 录音上传失败（Network Error）排查

| 项 | 值 |
|---|---|
| 现象 | 录音管理页上传 4.62MB mp3 连续 3 次失败，列表显示「上传失败 / Network Error / 0%」，页面提示「1 个文件上传失败」 |
| 排查 | ① 宿主 nginx access log 中该用户会话**完全没有** `POST /api/recordings/upload`（只有 GET 轮询，均 200）→ 请求未到达服务器；② 该用户请求在 **20:04–20:08 完全中断**，20:08:49 出现 401（20:05–20:06 部署重启导致会话失效），20:08:52 重新登录；③ 服务端用 curl 走同一 HTTPS 路径上传合法 mp3 → **HTTP 202**、0.29s，前端 nginx/后端/体积限制（201m / 200MB）均正常；④ 部署窗口内容器重启，客户端网络同时中断，axios 无响应 → `Network Error` |
| 结论 | 非服务端故障：失败发生在客户端/网络层（请求未发出），时间点与部署重启窗口重叠；重启亦使内存会话失效（用户随后被 401 并重新登录） |
| 改进（已上线） | 上传新增「网络层失败自动重试一次」；错误原因细化（HTTP 状态 + 后端 message / 网络错误 / 超时，替代裸 `Network Error`）；修正上传上限文案（对话框/提示原写 25MB，实际 `MAX_UPLOAD_BYTES` 默认 200MB，改为按配置动态展示） |
| 验证 | 前端 210 tests、lint/format/i18n/build 全绿；线上新入口 JS 200、`recordings` 深链 200、错误文案已进产物 |
| 备份 | `backups/fe-r4-20261110-201542/`（前端 dist） |
