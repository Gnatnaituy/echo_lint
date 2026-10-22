# EchoLint 生产化补齐计划（P1 / P2）

> 目标：把当前「功能闭环的内测版」补到可对外上线的产品。
> 原则：分批交付；每批必须 `mvn test`、`npm run build` / `npm test` 通过并单独提交；
> 依赖外部选型的 P2 项先落地适配器与接口，选型确定后只需实现适配器。
> 进度以本文件的 checkbox 为准。

## 状态总览

| 批次 | 内容 | 状态 |
|---|---|---|
| 1 | 身份/权限/复核工作流/操作审计（原 P0-1 作为 P1 前置） | ✅ 已完成（2026-10-20） |
| 2 | 录音元数据与筛选、报表导出、词库运营、合规抽样、通知告警、可观测性、工程化 | 2a ✅；2b-1 ✅；2b-2 ✅ |
| 3 | AI 治理（供应商/密钥/配额/成本/重试）、异步任务持久化、成本看板 | 3a ✅；3b-1 ✅；3b-2 ✅（P1 完成） |
| 4 | P2 可独立项：i18n、移动端、无障碍、大屏、开放 API/Webhook、知识库/SOP、前端性能、分片上传 | 4a ✅；4b ✅（P2 可独立项完成） |
| 5 | P2 外部依赖项：多租户、SSO/LDAP、实时转写、单声道说话人分离（适配器先行） | ✅ 已完成 |
| 6 | PostgreSQL 17 + pgvector 迁移、知识库语义检索（RAG） | RAG ✅ 已完成（2026-10-20）；数据迁移待演练 |

---

## Batch 1 · 身份、权限、复核工作流、操作审计

### 1.1 角色

`ADMIN`（管理员）/ `REVIEWER`（复核员）/ `VIEWER`（只读）。

### 1.2 认证（会话 Cookie）

使用 Spring Security 会话认证：`HttpOnly` + `SameSite=Lax` 的 JSESSIONID，登录后浏览器自动携带，
前端**不存 token**（这样 `<audio src="/uploads/...">` 也能带登录态）。

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/auth/login` | `{username,password}` → `{id,username,displayName,role}`；失败 401 |
| POST | `/api/auth/logout` | 注销当前会话 → 204 |
| GET | `/api/auth/me` | 当前用户；未登录 401 |
| POST | `/api/auth/password` | `{oldPassword,newPassword}` → 204 |

- 首个管理员：`APP_ADMIN_USERNAME`（默认 `admin`）/ `APP_ADMIN_PASSWORD`（默认 `admin12345`），
  仅当用户表为空时创建，启动日志 WARN 提示修改。
- 密码 BCrypt；用户名唯一、小写；密码最短 8 位。
- 401/403 返回 JSON：`{code,message,timestamp}`（由 Spring Security EntryPoint/DeniedHandler 实现）。

### 1.3 权限矩阵

- 匿名可访问：`/api/auth/login`、`/actuator/health`、`/actuator/info`。
- 仅 ADMIN：`/api/users/**`、`/api/audit-logs/**`、敏感词写操作（POST/PUT/DELETE `/api/dictionary/**`）、
  系统设置写操作（PUT/POST `/api/settings/**`）、语料删除。
- ADMIN + REVIEWER：`/api/reviews/**`、录音上传/重试/删除/认领/指派/重开。
- 其余 `/api/**` 与 `/uploads/**`：登录即可。
- 前端按角色隐藏入口，后端必须独立校验（不能只靠前端）。

### 1.4 用户管理（ADMIN）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/users` | 用户列表 |
| POST | `/api/users` | `{username,password,displayName,role}` |
| PUT | `/api/users/{id}` | `{displayName,role,enabled,password?}` |
| DELETE | `/api/users/{id}` | 禁用（不物理删除，保留审计引用） |

### 1.5 操作审计（ADMIN）

- `GET /api/audit-logs?page&size&actor&action`，按时间倒序分页；`size` 上限 100。
- 返回：`{id, actor, action, targetType, targetId, detail, ip, createdAt}`。
- 动作：`LOGIN`、`LOGOUT`、`USER_CREATE`、`USER_UPDATE`、`USER_DISABLE`、`RECORDING_UPLOAD`、
  `RECORDING_DELETE`、`RECORDING_RETRY`、`RECORDING_CLAIM`、`RECORDING_ASSIGN`、`REVIEW_SUBMIT`、
  `REVIEW_REOPEN`、`DICTIONARY_CREATE`、`DICTIONARY_UPDATE`、`DICTIONARY_DELETE`、`CORPUS_CREATE`、
  `CORPUS_DELETE`、`SETTINGS_UPDATE`。
- 审计写入失败**不得**影响主流程（try/catch + warn）。
- 审计记录当前登录人（Spring Security principal），请求 IP 取 `X-Forwarded-For` 首个或 remoteAddr。

### 1.6 复核工作流

- `Recording` 新增字段：`assignedTo`（用户名，可空）、`priority`（`LOW`/`NORMAL`/`HIGH`，默认 NORMAL）、
  `reviewCount`（int，默认 0）。
- `RecordingSummary` 新增：`assignedTo`、`priority`、`reviewCount`、`requiredApprovals`。
- `GET /api/recordings` 新增过滤：`assignedTo`、`priority`；`size` 上限 100。
- `POST /api/recordings/{id}/claim`：认领，仅 `NEEDS_REVIEW`；已被他人认领返回 409。
- `PUT /api/recordings/{id}/assign` `{username}`：ADMIN 指派。
- `POST /api/recordings/{id}/reopen`：ADMIN，仅终态（`VIOLATION_CONFIRMED`/`FALSE_POSITIVE`/`COMPLIANT`），
  重开为 `NEEDS_REVIEW`，清空最终结论字段，保留复核历史。
- `POST /api/reviews/{id}` `{result, violationType?, comment?}`：
  - 复核人 = 当前登录用户（忽略请求体里的 reviewer 字段）；
  - 每次提交写一条 `review_records`（reviewer、result、violationType、comment、round、createdAt）；
  - `reviewCount+1 >= requiredApprovals`（`app.review.required-approvals`，环境变量
    `APP_REVIEW_REQUIRED_APPROVALS`，默认 1）时落最终结论 + 写语料 + 词挖掘；
  - 未达标：保持 `NEEDS_REVIEW`，返回当前状态；
  - 双人复核时，同一用户不能连续复核同一条（409）。
- `GET /api/recordings/{id}/reviews`：复核历史（登录即可）。
- 复核并发：两人同时提交时，后提交者若状态已非 `NEEDS_REVIEW` 返回 409（沿用现有状态校验）。

### 1.7 前端

- `/login` 独立页（不套壳层）；登录成功跳工作台。
- 路由守卫：未登录 → `/login`；ADMIN 页面校验角色，无权限回工作台并提示。
- `AppHeader` 显示当前用户/角色；菜单：修改密码、退出登录。
- 侧栏按角色显示「用户管理」「操作审计」；非 ADMIN 隐藏敏感词/设置写操作。
- 复核页：队列展示指派/优先级；认领、指派（ADMIN）、优先级筛选、复核历史、双人复核进度；
  提交后按「已提交 1/2」「已完成」提示。
- 新增用户管理页、操作审计页。
- 401 统一跳登录；403 提示无权限；保留现有全部功能与视觉风格。
- 不新增第三方依赖（Vue 3 + Element Plus + axios 足够）。

### 1.8 验收

- 后端：`mvn -q -B test` 通过；新增认证/复核流单测。
- 前端：`npm run build`、`npm test` 通过。
- 手工验收（部署后）：匿名访问 API 返回 401；登录后按角色可见/可用；
  复核人记录为登录用户；审计页能看到上述动作；`/uploads/**` 未登录不可访问。

### 1.9 完成记录

- 后端：12 个测试类、81 个单测全绿；`mvn -B -DskipTests package` 成功。
- 前端：`npm run build` 成功；`npm test` 27/27 通过。
- 端到端冒烟（本机真实 MySQL + Spring 上下文，独立测试库）：
  匿名 401、登录/会话、ADMIN/REVIEWER/VIEWER 权限、上传与 `/uploads` 鉴权、审计动作、
  双人复核 1/2 → 2/2、同人连续复核 409、重开/指派、405/403 语义、分页 size 上限。
- 集成修复：Spring MVC 4xx（405/415/400）不再落成 500；无转写文本时跳过语料回馈；
  500 响应不再回显内部异常信息。
- 已知偏差：assign/reopen/语料删除收紧为仅 ADMIN；新增 `POST /api/recordings/{id}/delete`
  兼容别名；用户列表暂不分页；会话 Cookie 暂未设置 `Secure`（等 HTTPS 部署后开启）。

---

## Batch 2 · 报表与运营效率

### 2a 报表与运营（✅ 已完成）

- [x] 录音元数据：坐席、团队、通话时间、方向、业务线；列表支持日期范围/坐席/团队/方向筛选（时间优先 callTime，缺失回退 uploadTime）
- [x] 报表导出：录音清单/违规明细/复核记录 CSV（UTF-8 BOM、Excel 直接打开、≤10000 行、CSV 公式注入防护）；按坐席/团队/违规类型/日期统计
- [x] 敏感词批量导入/导出（CSV、≤5000 行/5MB）、批量启停、变更审计
- [x] 导出/词库写操作审计（REPORT_EXPORT / DICTIONARY_IMPORT / DICTIONARY_BULK_UPDATE）
- [x] CI（GitHub Actions：后端测试 + 前端构建/测试）

### 2b-1 平台加固（✅ 已完成）

#### 依赖与配置
- 新增依赖：`micrometer-registry-prometheus`、`springdoc-openapi-starter-webmvc-ui`、`flyway-core` + `flyway-mysql`。
- `application.yml`：
  - `management.endpoints.web.exposure.include: health,info,prometheus`
  - `server.shutdown: graceful`、`spring.lifecycle.timeout-per-shutdown-phase: 20s`
  - `spring.flyway.enabled: true`、`baseline-on-migrate: true`、`baseline-version: 1`、`locations: classpath:db/migration`
  - 保留 `ddl-auto: update`（后续迁移从 V2 开始，逐步转 validate）
- `src/main/resources/db/migration/V1__baseline.sql`：仅基线标记（注释即可），不重复建表。
- `SecurityConfig`：放行 `/actuator/health`、`/actuator/info`、`/actuator/prometheus`（后端端口仅回环/内网，由部署层限制）、
  `/v3/api-docs/**`、`/swagger-ui/**`、`/swagger-ui.html`。
- `docker-compose.yml`：backend/frontend 增加 `logging` 限制（max-size 10m、max-file 3）；backend 增加 `stop_grace_period: 30s`。

#### 上传安全与数据完整性
- `Recording` 新增 `audioSha256`（64 位 hex，唯一索引，历史数据可为 null）；
  新增复合索引：`(status,id)`、`(agentName,callTime)`、`(teamName,callTime)`、`(assignedTo,status)`。
- 上传时单次流式计算 SHA-256（边落临时文件边摘要，成功后原子移动到正式目录）；
  发现重复哈希 → 409，消息带已存在的录音 id；失败/重复时清理临时文件。
- 文件头预检：新增 `AudioFileValidator`，按扩展名校验 magic bytes（mp3/wav/flac/ogg/opus/m4a/mp4/mov/webm/aac/amr/caf）；
  不匹配 → 400「文件内容与扩展名不符」。现有上传单测需要提供合法文件头。
- 删除录音时级联删除 corpus 派生数据（`CorpusEntryRepository.deleteByRecordingId`）。

#### 事务与异步
- `ReviewService` 最终结论落库后，词挖掘（AI，可能耗时数百秒）移出事务：
  使用 `TransactionSynchronizationManager` afterCommit 触发异步方法（`@Async("pipelineExecutor")` 或独立 executor），
  异常吞掉只记日志；无事务上下文时（单测）直接调用。

#### 验收
- 后端 `mvn -q -B clean test` 全绿、`mvn -q -B -DskipTests package` 成功。
- 本机 MySQL（127.0.0.1:3307，audit/audit123，可建临时库）启动打包 jar 验证：
  `/actuator/prometheus` 200、`/v3/api-docs` 200、Swagger UI 200、Flyway 基线写入 `flyway_schema_history`、
  重复上传 409、伪造扩展名 400、删除录音级联清理 corpus、应用日志 0 ERROR；验证后清理临时库与文件。

### 2b-1 完成记录

- 后端 163 个单测全绿（新增 30 个）；`mvn -B -DskipTests package` 成功。
- 冒烟（打包 jar + 临时 MySQL）：
  - 全新库：Flyway V1 执行、预置 191 词 / 8 语料；既有表库：BASELINE v1、自动补 `audio_sha256` 列、种子幂等。
  - `/actuator/prometheus`、`/v3/api-docs`、`/swagger-ui/index.html` 均 200。
  - 合法 mp3 上传 202；同字节重复 409（带已存在 id，目录无残留）；伪造 .mp3 400；API 不返回 audioSha256。
  - SIGTERM 后 2s 优雅停机；日志 0 ERROR。
- 额外加固：compose 三个端口（3307/8082/8081）只绑回环；容器日志轮转。
- 已知限制：结构化 JSON 日志未做；需要部署层限制 prometheus/导出接口为内网；`ddl-auto=update` 仍启用。

### 2b-2 运营与 AI 质量（✅ 已完成）

#### 合规抽样
- 配置 `app.sample.dfa-miss-rate`（env `APP_SAMPLE_DFA_MISS_RATE`，0~1，默认 0）。
- `Recording` 新增 `sampled`（boolean，默认 false），Summary/详情/CSV 透出。
- PipelineService：DFA 无命中时按概率进入抽样；抽样录音继续走 AI 语义复筛
  （AI 判违规 → NEEDS_REVIEW；AI 判合规 → COMPLIANT 并在 pipeline log 标注「抽样复核」；
  AI 不可用 → 降级 NEEDS_REVIEW，不允许静默放行）。
- 报表 recordings.csv 增加 sampled 列；统计可返回 sampled 数量。

#### 通知告警（Webhook）
- 配置：`app.notify.enabled`、`app.notify.webhook-url`、`app.notify.webhook-secret`
  （可选 HMAC-SHA256 头 `X-EchoLint-Signature`）、`app.notify.timeout-seconds`、
  `app.notify.backlog-threshold`（默认 50）。
- `NotificationService` 异步发送 JSON：`{event, occurrenceTime, recordingId?, title, detail, link?}`；
  事件：`REVIEW_CONFIRMED_VIOLATION`（复核最终确认违规）、`RECORDING_FAILED`（流水线失败）、
  `REVIEW_BACKLOG`（每日定时，NEEDS_REVIEW 超过阈值）、`NOTIFY_TEST`。
- 发送失败只记 warn，不影响主流程；URL 仅来自配置（避免运行时 SSRF）。
- `@EnableScheduling` + 每日定时检查积压；时区 Asia/Shanghai。
- API（ADMIN）：`GET /api/settings/notifications` 返回 enabled/urlConfigured/threshold/事件列表；
  `POST /api/settings/notifications/test` 发送测试事件并返回结果。
- 审计动作 `NOTIFY_TEST`。

#### AI 全文分块与严格解析
- `app.screen-models.chunk-chars`（默认 12000）、`app.screen-models.max-chunks`（默认 6）、
  `app.screen-models.min-confidence`（默认 0.6）。
- 转写文本超长时分块（带重叠）逐块调用模型；任一块违规即整体违规（取最高置信度合并原句/理由），
  每块调用都写 `ai_invocations` 留痕；最多 max-chunks 块，超出截断并在日志注明。
- 严格解析：缺少 `violation` 布尔字段/返回非 JSON → 视为解析失败（降级人工，不得自动放行）；
  `violation=true` 且置信度 < min-confidence → 降级 NEEDS_REVIEW（degraded=true）；
  `violation=false` 才可自动 COMPLIANT。
- 单测覆盖：分块合并、缺字段、低置信度、正常合规。

#### 前端
- 系统设置页新增「抽样与通知」卡片（ADMIN）：显示抽样比例、Webhook 是否配置、积压阈值，
  提供「发送测试 Webhook」按钮与结果提示；非 ADMIN 只读/隐藏。
- 录音列表、复核队列、详情抽屉显示「抽样」标记；报表页汇总卡增加「抽样数」。
- 审计页补 `NOTIFY_TEST` 文案；API client 增加 notifications 接口。
- 不新增依赖。

#### 验收
- 后端 `mvn clean test` 全绿；前端 build/test 全绿。
- 本机 MySQL + 打包 jar 冒烟：抽样比例 1.0 时 DFA 未命中也进 AI/人工；
  Webhook 测试可收到（用本地 http 接收器验证 JSON 与签名）；超长文本分块调用次数正确；
  缺 violation 字段的模型回复被降级。


- [ ] 合规抽样：DFA 未命中的录音按比例进入 AI/人工抽检（可配置）
- [ ] 通知告警：Webhook（企微/钉钉/自定义），高违规、待复核积压、处理失败
- [ ] 可观测性：Micrometer + Prometheus、结构化日志、Docker 日志轮转（异常脱敏已在 Batch 1 完成）
- [ ] 工程化：OpenAPI（springdoc）、Flyway 基线迁移、优雅停机、上传去重/文件头预检
- [ ] 数据完整性：删除录音级联清理 corpus、复合索引、事务内 AI 调用移出
- [ ] AI 质量：复筛全文分块、缺字段/低置信度不得自动放行

### 2a 完成记录

- 后端 133 个单测全绿（新增 52 个）；前端 build + 32 个单测全绿。
- 端到端冒烟（真实 MySQL + 打包 jar）：带元数据上传、5 种过滤、stats/report、
  三类 CSV（BOM/文件名/列头/转义）、词库导出/导入/批量启停、VIEWER 导出 200 / 词库 PATCH 403、
  审计落库，应用日志 0 ERROR。
- 安全加固：CSV 公式注入前置单引号；词库导入 5MB 上限。
- 已知限制：导出超 10000 行静默截断；reviews.csv 按复核时间过滤；CSV 中枚举输出英文名；
  报表页的坐席/团队/方向筛选仅作用于导出（汇总卡按日期范围统计，界面已注明）。

### 2b-2 完成记录

- 后端 207 个单测全绿（新增 44 个）；`mvn -B -DskipTests package` 成功；前端 build + 43 个单测全绿。
- 冒烟（打包 jar + 临时 MySQL + 本地 whisper/model/webhook 桩）：
  - 抽样率 1.0：DFA 未命中 → `sampled=true` → 分块 3 次调用（6179 字符 / max-chunks=3）+ 3 条 AI 留痕 + 截断日志；后段违规合并为 NEEDS_REVIEW。
  - 缺 `violation` 字段的模型回复 → `degraded=true` → NEEDS_REVIEW（未自动放行）。
  - Webhook：测试事件、复核确认违规、流水线失败三类均收到，HMAC `X-EchoLint-Signature` 校验通过；失败只 WARN。
  - 权限：通知设置 GET 仅 ADMIN（401/403 正确）；复筛模型只读信息仍对登录用户开放。
  - `stats.sampled`、`recordings.csv` 的 `sampled` 列、审计 `NOTIFY_TEST` 均验证。
- 偏差：未加 Flyway V2（`sampled` 列仍由 ddl-auto 添加，生产升级前需备份）；通知设置 GET 未返回抽样比例（前端以 env 说明占位）。
- 后续（Batch 3）：AI 供应商后台 CRUD/密钥加密、配额与成本、异步任务持久化、成本看板、模型 A/B。

## 3a · AI 治理与成本（✅ 已完成）

### 密钥与供应商
- `APP_SECRET_KEY`：AES 主密钥（Base64 32 字节或任意字符串经 SHA-256 派生）。未配置时用开发默认值并 WARN。
- 新增 `SecretCipher`（AES/GCM/NoPadding，随机 12 字节 IV，Base64(iv||ciphertext)），密钥只存密文 + 末 4 位掩码。
- 新表 `ai_providers`：key(unique)、label、base_url、chat_path、api_key_cipher、api_key_masked、
  default_model、models_json、json_mode、max_tokens、input_price_per_1k、output_price_per_1k、
  enabled、built_in、created_at、updated_at。
- 启动时把 yml `app.screen-models.providers` 中不存在的供应商加密落库（built_in=true），不删除库中已有自定义项。
- `ScreenModelService` 改为从注册表（DB 优先，yml 兜底）解析供应商，密钥解密后按调用构造 WebClient；
  `PUT /api/settings/screen-model` 需校验供应商存在且 enabled。
- API（ADMIN）：
  - GET `/api/settings/providers`（密钥只回掩码/是否配置）
  - POST `/api/settings/providers`（自定义供应商，key 唯一）
  - PUT `/api/settings/providers/{key}`（apiKey 可选，空表示不修改）
  - DELETE `/api/settings/providers/{key}`（仅自定义、且非当前生效）
  - POST `/api/settings/providers/{key}/test`（可选 model）
- 审计动作：PROVIDER_CREATE / PROVIDER_UPDATE / PROVIDER_DELETE / PROVIDER_TEST。

### 用量与成本
- `AiInvocation` 增加 inputTokens、outputTokens、costMicros（Long，1e-6 USD）。
- 从模型响应 `usage.prompt_tokens / completion_tokens` 提取；按供应商单价（每 1K tokens）计算成本，调用时快照。
- `GET /api/stats/ai-usage?from&to`（ADMIN）：requests / inputTokens / outputTokens / costUsd，
  以及 byDay / byProvider / byModel 明细。
- 前端新增 `/costs`（ADMIN）成本看板：汇总卡 + 日期范围 + byDay/byProvider/byModel 表格。

### 配额与重试
- 配置 `app.screen-models.daily-token-limit`（0=不限）与 `requests-per-minute`（0=不限）；
  调用前检查当日 tokens（DB）与进程内每分钟请求数，超限抛错 → 流水线降级人工。
- 对 429/5xx 做最多 2 次重试（500ms/1500ms 退避）；最终失败仍走降级。
- 环境变量：APP_SCREEN_DAILY_TOKEN_LIMIT / APP_SCREEN_REQUESTS_PER_MINUTE。

### 前端
- 系统设置页「AI 供应商」管理（ADMIN）：列表（掩码/启用/内置标记/价格）、新增、编辑、删除（自定义）、连通性测试、
  仍可切换当前生效供应商/模型。
- 新增 `/costs` 成本看板（ADMIN），侧栏入口；API client 增加 providers/ai-usage。
- 不新增依赖；保持现有视觉与原功能。

### 验收
- 后端 `mvn clean test`、前端 build/test 全绿。
- 本机 MySQL 冒烟：内置供应商加密落库、掩码正确、CRUD/测试接口、切换模型、
  成本统计（构造带 usage 的调用）、超配额降级、429 重试。
- 明确不在本批：模型 A/B、评测样本集、异步任务持久化（放 Batch 3b）。

### 3a 完成记录

- 后端 275 个单测全绿（新增/改写 68 个）；前端 build + 53 个单测全绿。
- 冒烟（打包 jar + 临时 MySQL）：
  - 内置供应商启动加密落库（AES-GCM，密文 64 字符、DB 无明文、掩码 `****1234`/`****5678`），重启幂等不覆盖。
  - 供应商 CRUD / 密钥轮换（留空保持）/ 测试端点 / 内置与生效供应商删除守卫 / 未知或停用供应商切换拒绝，均符合预期。
  - `GET /api/settings/providers`、`GET /api/stats/ai-usage` 仅 ADMIN（reviewer 403）；`GET /api/settings/screen-model` 仍登录可读。
  - ai-usage：空形状正确；构造调用后汇总与 byDay/byProvider/byModel 正确（失败调用不计入）。
  - 审计 PROVIDER_CREATE/UPDATE/DELETE/TEST 落库且不包含密钥。
- 已知限制：内置供应商单价默认 0（需在设置页录入后成本才有金额）；`APP_SECRET_KEY` 变更后旧密文不可解密；配额/每分钟计数为全局/单实例语义；PUT 供应商为全量更新（apiKey 可选）。

## 3b-1 · 异步任务持久化（✅ 已完成）

### 数据模型
- 新表 `pipeline_tasks`：id、recording_id、status（PENDING/RUNNING/SUCCEEDED/FAILED/DEAD）、
  attempts、max_attempts（默认 3）、next_attempt_at、last_error、created_at、updated_at、finished_at；
  索引 `(status,next_attempt_at)`、`(recording_id)`。

### 队列行为
- `PipelineTaskService.enqueue(recordingId)`：同一录音已有 PENDING/RUNNING 任务时幂等返回，否则建 PENDING（attempts=0，nextAttemptAt=now）。
- 调度器 `@Scheduled(fixedDelay=5000)` 每次最多取 5 条到期的 PENDING，事务内标记 RUNNING 后提交到 pipeline executor 执行；
  成功 → SUCCEEDED；失败 → attempts+1；未达 max_attempts → PENDING + 退避（30s/120s/300s 封顶），达到 → DEAD。
- 最终失败（DEAD）才发 `RECORDING_FAILED` Webhook；中间重试不重复告警。
- `PipelineService` 增加 `processNow(recordingId)`：执行流水线，失败时照旧落 FAILED + 错误信息并抛出，供任务层判断重试。
- `RecordingService.upload/retry` 改为 enqueue（不再直接 `pipelineService.run`）。
- 启动恢复：RUNNING 任务 → PENDING（nextAttemptAt=now，不丢任务）；中间态录音若仍有活动任务不强制 FAILED。

### API / 前端
- `GET /api/tasks?status=&recordingId=&page=&size=`（ADMIN，size≤100）：任务分页视图（含录音文件名、状态、attempts/max、nextAttemptAt、lastError、时间）。
- `POST /api/tasks/{id}/retry`（ADMIN）：DEAD/FAILED → PENDING、attempts=0；审计 `TASK_RETRY`。
- 前端新增 `/tasks`「任务队列」（ADMIN）：状态筛选、表格、失败原因、重试按钮、5s 自动刷新；侧栏 ADMIN 入口；审计文案。
- 不新增依赖；保持现有功能。

### 验收
- 后端 `mvn clean test`、前端 build/test 全绿。
- 本机 MySQL 冒烟：上传后任务入队→执行→成功；构造失败任务验证指数退避与 DEAD；重启后 RUNNING 任务恢复；任务 API 分页/重试/权限。

### 3b-1 完成记录

- 后端 319 个单测全绿（新增 44 个）；前端 build + 63 个单测全绿。
- 冒烟（打包 jar + 临时 MySQL + 本地 webhook）：
  - 上传后任务入队 → 5s 内执行；失败按 30s/120s/300s 退避，第 3 次失败进入 DEAD；仅 DEAD 收到 1 条 `RECORDING_FAILED` 告警（重试期间不重复告警）。
  - 管理员重试 DEAD → PENDING attempts=0；重复点击 409；审计 `TASK_RETRY` 落库。
  - 权限：任务 API 匿名 401 / REVIEWER 403 / ADMIN 200；分页 size 上限 100、按状态/录音过滤、含录音文件名。
  - 重启恢复：RUNNING → PENDING 重新执行；有活动任务的中断录音不强制 FAILED；无任务的中断录音仍置 FAILED。
  - 录音手动重试只保留一条活动任务（幂等）；日志 0 ERROR。
- 已知限制：pipeline_tasks 未加 Flyway V2；删除录音不清理任务历史行；停机瞬间线程池拒单可能短暂 RUNNING，由启动恢复兜底。

## 3b-2 · 模型 A/B 与评测（✅ 已完成，P1 收尾）

### A/B 分流
- 配置 `app.screen-models.ab.enabled`（默认 false）、`ab.percent`（0~100）、`ab.provider-id`、`ab.model`（可空）。
- `ScreenModelService.activeForScreening()`：开启且备用供应商可用时，按 percent 概率走 B 组；否则 A 组（当前生效）。
  仅语义复筛参与分流，词挖掘仍走当前生效模型。
- AI 结论 JSON 增加 `screenVariant`（"A"/"B"）；provider/model 仍照旧记录，成本报表可按供应商/模型对比。
- `GET /api/settings/screen-model` 返回 ab 配置（只读）。
- 测试：percent 0/100、关闭、备用不可用时回退 A、结论含 variant。

### 模型评测
- `POST /api/settings/screen-model/evaluate`（ADMIN）`{providerId, model?, sampleSize?}`
  （sampleSize 默认 10，上限 20）。
- 从语料库取已标注样本（VIOLATION / COMPLIANT 均衡抽样），逐条用复筛同款提示词与严格解析调用模型；
  返回：
  `{total, evaluated, correct, accuracy, precision, recall, tp, fp, tn, fn, latencyMs,
    byLabel:[{label,total,correct}], results:[{corpusId,expected,predicted,confidence,reason}]}`
- 单条失败计入 failed（不参与 accuracy/precision/recall 分母），结果中标注 error；调用前过 AI 配额。
- 审计动作 `MODEL_EVALUATE`；`/api/settings/**` POST 已 ADMIN。
- 前端系统设置页新增「A/B 与模型评测」卡片（ADMIN）：
  展示 A/B 配置（只读）；选择供应商/模型/样本数 → 运行评测；展示 accuracy/precision/recall 与逐条结果表。
- 不新增依赖；评测为管理员手动触发，单次样本数受限。

### 验收
- 后端 `mvn clean test`、前端 build/test 全绿。
- 单测：A/B 选择确定性、评测指标（tp/fp/tn/fn、precision/recall/accuracy）、失败样本处理。
- 本机 MySQL + 真实桩（本地兼容模型）冒烟：A/B percent=100 走 B 且结论含 variant；
  评测返回指标与逐条结果；权限与审计正确。



- [ ] 异步任务持久化（DB 任务表/Outbox）、自动重试、死信、任务视图
- [ ] 模型 A/B 分流 + 评测样本集

## Batch 3 · AI 治理与异步可靠性（原始清单）

- [ ] AI 供应商后台 CRUD（不再改 YAML 重启）、密钥加密存储与轮换
- [ ] token 成本统计、配额/限流、失败退避重试、超时细分
- [ ] 模型效果评测：A/B 分流 + 样本集
- [ ] 异步任务持久化（DB 任务表/Outbox）、自动重试、死信、任务视图
- [ ] 成本看板页面

## Batch 4 · P2 可独立项

- [ ] i18n（中/英）、移动端布局、无障碍（aria/键盘）
- [ ] 大屏视图
- [ ] 开放 API（API Key）+ Webhook 管理
- [ ] 知识库/SOP 关联
- [ ] 前端拆包与 gzip/静态缓存
- [ ] 批量上传、分片/断点续传、上传进度

### Batch 2a 契约（本轮实施）

#### 录音元数据（后端）
- `Recording` 新增字段（均可空，历史数据兼容）：`agentName`、`teamName`、`callTime`（LocalDateTime）、
  `direction`（枚举 `INBOUND`/`OUTBOUND`/`UNKNOWN`，默认 UNKNOWN）、`businessLine`。
- `POST /api/recordings/upload` 增加可选 multipart 参数：`agentName`、`teamName`、`callTime`
  （ISO-8601，如 `2026-10-20T09:30:00`）、`direction`、`businessLine`；长度与枚举校验。
- `RecordingSummary`（列表）与详情返回这些字段。
- `GET /api/recordings` 新增过滤：`agentName`、`teamName`、`direction`、`from`、`to`（日期/日期时间；
  优先按 `callTime` 过滤，`callTime` 为空时回退 `uploadTime`；`to` 含当天）。`size` 仍上限 100。

#### 报表与导出（后端）
- `GET /api/reports/recordings.csv`：与录音列表相同的过滤参数，UTF-8 BOM，最多 10000 行，
  列：id、文件名、坐席、团队、通话时间、方向、业务线、状态、命中数、违规类型、AI 判定、
  复核人、复核时间、上传时间、时长、错误信息。文件名 `recordings-<date>.csv`。
- `GET /api/reports/violations.csv`：仅 `VIOLATION_CONFIRMED`，含违规类型/复核人/备注。
- `GET /api/reports/reviews.csv`：复核记录 + 录音元数据（轮次、复核人、结论、类型、备注、时间）。
- `GET /api/stats/report?from=&to=`：返回 total、按状态、按违规类型、按坐席 Top、按团队 Top、按日统计。
- 权限：登录用户（含 VIEWER）可导出；写审计动作 `REPORT_EXPORT`。

#### 词库运营（后端）
- `GET /api/dictionary/export`：CSV（word,category,severity,source,enabled,hitCount,createdAt）。
- `POST /api/dictionary/import`：multipart CSV（允许表头，列 word,category,severity,enabled；≤5000 行），
  返回 `{created, skipped, invalid}`；规范化后去重；重建 DFA。
- `PATCH /api/dictionary/bulk-enabled` `{ids:[], enabled:bool}` → `{updated}`；重建 DFA。
- 新增审计动作 `DICTIONARY_IMPORT`、`DICTIONARY_BULK_UPDATE`。

#### 前端
- 录音上传改为「元数据 + 文件」对话框：坐席、团队、通话时间（日期时间选择）、方向、业务线；
  上传后清空表单。
- 录音列表：新增坐席/团队/通话时间列；筛选增加日期范围、坐席、团队、方向。
- 新增「报表」页 `/reports`：日期范围与维度筛选、汇总卡、按坐席/团队/违规类型表格、
  导出录音/违规/复核 CSV 按钮（axios blob 下载）。
- 敏感词库：导出 CSV、导入 CSV 对话框（展示 created/skipped/invalid）、表格多选 + 批量启用/停用。
- 侧栏加入「报表」（所有登录角色可见）；API client 增加对应方法与 blob 下载辅助。
- 不新增第三方依赖；保持现有视觉风格与全部现有功能。

#### 验收
- 后端 `mvn -q -B test` 全绿；前端 `npm run build` + `npm test` 全绿。
- 带元数据上传后，列表/统计/导出能按坐席、团队、日期筛选。
- CSV 含 UTF-8 BOM，Excel 直接打开中文不乱码。
- 词库导入去重、批量启停后 DFA 生效。


## 5 · 集成适配器（✅ 已完成）

> 目标：把需要外部选型/部署的能力做成**可插拔接口 + 配置 + 状态 API + 接入文档**，
> 默认关闭、零行为变化；选型确定后只需实现适配器，不改主流程。

### 多租户（租户解析适配器）
- 接口 `TenantResolver { String resolve(HttpServletRequest) }`；默认 `SingleTenantResolver` 返回 `default`。
- 配置 `app.tenant.enabled=false`、`app.tenant.mode=SINGLE|HEADER`、`app.tenant.header=X-Tenant-Id`。
  HEADER 模式下缺失/非法租户 ID → 400 JSON。
- `TenantContext`（ThreadLocal）+ `TenantFilter`（/api/**，设置/清理上下文）。
- **本轮不做数据隔离**：在 docs/integrations.md 给出 tenant_id 列、查询过滤、迁移与回滚的分阶段方案。

### SSO / LDAP（身份源适配器）
- 接口 `SsoProvider { id(); enabled(); configured(); loginUrl(); metadata() }`；默认 `DisabledSsoProvider`。
- 配置 `app.sso.enabled=false`、`app.sso.provider=OIDC|LDAP`、`issuer`、`client-id`、`client-secret`、
  `app.sso.role-mapping`（JSON 字符串）。状态/接口绝不返回 secret。
- 公开只读接口 `GET /api/auth/sso/config`（permitAll）：`{enabled, provider, configured, loginUrl}`，供登录页展示。
- 真正 OIDC/LDAP 对接需引入对应客户端依赖与 IdP，文档给出步骤与安全注意（state/nonce、角色映射、登出）。

### 实时转写（流式 ASR 适配器）
- 接口 `StreamingTranscriptionProvider`：`id()/configured()/start(config)` + `StreamSession{send,finish,close}`；
  默认 `UnsupportedStreamingProvider`（显式抛出未配置）。
- 配置 `app.asr.streaming.enabled=false`、`provider`、`endpoint`、`api-key`（状态只显示是否配置）。
- 不改变现有批量转写流程；文档说明接入流式 ASR（WebSocket/SSE）与前端实时展示的改造点。

### 说话人分离（diarization 适配器）
- 接口 `DiarizationProvider`：`id()/configured()/diarize(Path) -> List<SpeakerSegment>`；
  默认 `UnsupportedDiarizationProvider`。
- 配置 `app.diarization.enabled=false`、`provider`、`endpoint`、`api-key`。
- 单声道录音才需要；双声道仍按现有左右声道方案。文档给出与分段的合并/降级策略。

### 集成状态 API 与文档
- `GET /api/settings/integrations`（ADMIN）：
  `{tenant:{enabled,mode,provider,configured}, sso:{enabled,provider,configured,loginUrl},
    streaming:{enabled,provider,configured}, diarization:{enabled,provider,configured}}`，无 secret。
- 新增 `docs/integrations.md`：四类集成的配置项、接口契约、接入步骤、安全与回滚注意事项；
  README 增加入口与配置表。
- 单测：各适配器默认关闭、配置解析、租户模式/缺失 header、状态 API 与权限、secret 不外泄。

### 验收
- 后端 `mvn clean test` 全绿（无新依赖）。
- 启动冒烟：默认全关 → 状态 API 全 disabled；租户 HEADER 模式缺失 header 400、合法 header 通过；
  SSO 公开状态接口匿名可读且不含 secret；应用日志 0 ERROR。

### 5 完成记录

- 后端 438 个单测全绿（新增 52 个）；`mvn package` 成功；零新依赖。
- 冒烟（打包 jar + 临时 MySQL，三轮）：
  - 默认全关：启动日志四类「关闭」，`/api/settings/integrations` 匿名 401 / VIEWER 403 / ADMIN 200，四类均 disabled，响应无密钥字段。
  - 租户 HEADER 模式：缺失/非法 header 400 JSON 且不回显原值；合法 header 下登录/SSO 配置/状态接口均正常；OPTIONS 预检放行。
  - 已配置适配器：状态与公开 SSO 配置正确；响应与日志 0 处密钥；应用日志 0 ERROR；优雅停机正常。
- 文档：`docs/integrations.md`（配置、接口契约、OIDC/LDAP 接入步骤、租户隔离迁移与回滚、流式/diarization 接入点、安全清单）；README 增加章节与配置表/API。
- 已知限制：SSO `loginUrl` 默认空（客户端适配器未实现，前端不应展示 SSO 入口）；HEADER 租户对全部 `/api/**` 生效（生产建议网关注入或登录态推导）；本轮不做数据隔离；流式 ASR/diarization 仅接口+配置+状态，未接入现网调用路径。

## 审计补充项（并入 Batch 2/3）

以下来自前端/后端独立审计（不在最初 P1/P2 清单里，但属于生产化必须项）：

### Batch 2 追加
- [ ] AI 复筛全文：当前只送前 12000 字符（`SemanticScreeningService`），DFA 全文命中但模型看不到尾部 → 分块/分段复核
- [ ] AI 结果严格解析：缺字段/低置信度不得自动判合规（violation 必填、置信度阈值可配），避免合法缺字段 JSON 直接放行
- [ ] 删除录音时级联清理 corpus 派生数据；增加软删与保留期标记
- [ ] 上传去重（音频 SHA-256）与幂等；上传后 ffprobe/文件头预检
- [ ] 数据库：status+id 复合索引、transcript 全文索引、ENUM→varchar、事务内 AI 调用移出事务
- [ ] 统一所有列表 size 上限；优雅停机 `server.shutdown=graceful`
- [ ] 部署加固：TLS/域名、8082/3307 只绑回环、容器内存限制与非 root、默认密码强制修改

### Batch 3 追加
- [ ] 外部调用重试/熔断/超时细分；AI 服务的 token/成本与音频哈希留痕
- [ ] 不可篡改审计（哈希链或只追加表）与访问审计（谁查看了哪条录音）
- [ ] 数据备份/恢复脚本与演练（MySQL + uploads 卷）

## 4a · 开放 API 与知识库（✅ 已完成）

### 开放 API（API Key 外部接入）
- 新表 `api_keys`：id、name(≤64)、key_prefix、key_hash(SHA-256, unique)、enabled、
  created_by、created_at、last_used_at。
- 密钥格式 `elk_` + 32 字节随机（base64url）；**只存 SHA-256 哈希**；创建时返回一次明文；
  列表只返回前缀 + 末 4 位掩码。
- 认证：请求头 `X-API-Key`，仅作用于 `/api/open/**`；无效/停用返回 JSON 401；
  成功后更新 `last_used_at`。`/api/open/**` 在 SecurityConfig 放行，由专用过滤器校验。
- 开放接口：
  - `POST /api/open/recordings/upload`（multipart `file` + 可选元数据 agentName/teamName/callTime/direction/businessLine）
    → 202 `{id,status,fileName}`；入队流水线；审计 `OPEN_API_UPLOAD`（actor=api-key:name）。
  - `GET /api/open/recordings/{id}` → `{id,status,fileName,uploadTime,processedTime,durationSeconds,
    hitCount,violationType,reviewResult,errorMessage}`。
- 管理（ADMIN）：`GET /api/api-keys`、`POST /api/api-keys {name}`（返回一次明文）、
  `DELETE /api/api-keys/{id}`（停用）；审计 `API_KEY_CREATE` / `API_KEY_REVOKE`。

### 知识库 / SOP
- 新表 `knowledge_entries`：id、title、category、violation_type（可空）、content(TEXT)、
  tags（逗号分隔）、enabled、created_by、created_at、updated_at。
- API：`GET /api/knowledge?keyword=&category=&enabled=&page=&size=`（登录可读）；
  POST/PUT/DELETE（ADMIN + REVIEWER）；审计 KNOWLEDGE_CREATE/UPDATE/DELETE。
- 前端新增 `/knowledge` 页面：搜索/分类/启用筛选、列表、详情抽屉、新增/编辑（角色控制）、删除确认；
  侧栏「知识库」对所有登录用户可见。

### 前端（另）
- `/api-keys` 页面（ADMIN）：列表（名称/前缀/状态/创建人/最近使用）、创建对话框（明文仅显示一次 + 复制）、停用。
- 侧栏 ADMIN 菜单增加「API 密钥」；知识库入口所有人可见。
- README 增加外部系统用 `X-API-Key` 上传录音的 curl 示例。

### 验收
- 后端 `mvn clean test`、前端 build/test 全绿。
- 冒烟：创建 key（明文一次）、列表掩码、停用后 401、用 key 上传录音并查询状态、审计；
  知识库 CRUD、权限（READER 只读、ADMIN/REVIEWER 可写）。

### 4a 完成记录

- 后端 386 个单测全绿（新增/改写 37 个）；前端 build + 81 个单测全绿。
- 冒烟（打包 jar + 临时 MySQL）：
  - API Key：创建返回一次明文（`elk_`+43）；列表仅前缀+末 4 位；DB 只存 SHA-256 无明文；缺失/无效/停用均 401，停用立即生效；`last_used_at` 更新。
  - 开放上传：带元数据 202、入队；查询仅返回 10 个限定字段；审计 `OPEN_API_UPLOAD`（actor=api-key:name）。
  - 知识库：ADMIN/REVIEWER 可写、VIEWER 写 403 读 200、匿名 401；keyword/category/enabled/分页（size≤100）；审计 KNOWLEDGE_*。
- 已知限制：API Key 列表不分页（前端全量拉取后分页）；知识库 PUT 为全量替换；开放 API 无限流/租户隔离，任一键可查任意录音的限定状态；`last_used_at` 每请求同步写库。

## 4b · UI/UX 与前端性能（✅ 已完成）

### i18n（中/英）
- 新增 `src/i18n/`（zh.js / en.js / index.js）：reactive locale、`t(key)`、`setLocale`、localStorage 持久化、`useI18n`。
- 覆盖范围：页头/侧栏导航/登录页/路由标题/通用按钮（保存/取消/删除/搜索/重置/确定）与大屏标签；
  其它业务页面文案**增量迁移**（在 README 注明当前覆盖范围，未翻译 key 回退中文）。
- 页头增加语言切换（中文/EN），持久化；`document.documentElement.lang` 同步。
- 单测：zh/en key 对齐、缺失 key 回退、locale 切换/持久化。

### 响应式与无障碍
- ≤900px：侧栏改为抽屉（遮罩/ESC 关闭/焦点管理），页头紧凑，表格横向滚动，弹窗宽度自适应。
- 图标按钮补 `aria-label`/`title`；增加 `:focus-visible` 样式；增加 skip-to-content 链接；抽屉键盘可关闭。
- 单测覆盖可测的纯逻辑（如断点判断/抽屉状态）。

### 数据大屏
- 新增 `/screen` 全屏视图（无侧栏/页头壳层，登录后可用）：KPI（总量/今日/待复检/违规率）、近 7 日趋势、
  违规类型分布、Top 坐席/团队（取 `/api/stats/report` 与 `/api/stats/overview`）、自动刷新 15s、时钟、退出按钮。
- 路由 meta.fullscreen；App.vue 全屏路由不渲染壳层；样式适配 1080p/4K。
- 单测：数据归一化/格式化 helper。

### 前端性能与缓存
- vite.config：manualChunks 拆分 vue / element-plus / icons / charts，控制首屏体积；保留懒加载。
- nginx.conf：gzip（js/css/json/svg）、`/assets/` 长缓存（immutable）、index.html no-cache；
  确保 SPA fallback 与 API 代理不变。
- 记录构建前后 chunk 体积变化。

### 批量上传
- 录音上传对话框支持多文件选择；元数据应用到本批全部文件；**顺序上传**、逐文件进度（axios onUploadProgress）、
  成功/失败/取消剩余、汇总结果；保留单文件路径与现有功能。
- 单测：上传队列纯逻辑（进度/汇总/取消）。

### 验收
- `cd frontend && npm run build && npm test` 全绿；构建 chunk 体积有改善。
- 手工：语言切换生效并持久化；窄屏抽屉可用；/screen 可打开并自动刷新；多文件上传进度与结果正确。

### 4b 完成记录

- 前端 build 通过、106 个单测全绿（新增 25 个）；后端无改动（386 测试保持）。
- 构建：入口 chunk 1,297.44 kB → 55.04 kB（gzip 419.32 → 19.10）；拆出 vue/element-plus/icons/vendor/charts，
  第三方依赖可长期缓存；新增 /screen 懒加载路由。nginx 增加 gzip、/assets/ immutable、index.html no-cache。
- i18n：中/英基础设施 + 页头/侧栏/登录/路由标题/通用按钮/大屏文案迁移，语言切换持久化，未翻译 key 回退中文；
  覆盖范围与新增 key 流程记录在 `frontend/src/i18n/README.md`。
- 响应式/无障碍：≤900px 侧栏抽屉（遮罩/ESC/焦点归还）、紧凑页头、表格横向滚动、focus-visible、skip-to-content、aria-label。
- 数据大屏 `/screen`：KPI、7 日趋势、违规类型分布、Top 坐席/团队、15s 刷新、时钟、退出；登录可用、全屏无壳层。
- 批量上传：多文件选择、元数据应用全批、顺序上传 + 逐文件进度 + 取消剩余 + 汇总，保留单文件流程。
- 已知限制：i18n 为增量迁移（业务页仍中文）；抽屉未实现完整 Tab 焦点陷阱；取消剩余不中断当前文件；Element Plus/图标仍全量注册，首屏 gzip 总量基本持平。

## Batch 5 · P2 外部依赖项（适配器先行）

- [ ] 多租户：租户模型、数据隔离、租户管理
- [ ] SSO/LDAP：OIDC/SAML 适配器（IdP 由部署方提供）
- [ ] 实时转写：流式 ASR 适配器（供应商由部署方提供）
- [ ] 单声道说话人分离：外部 diarization 适配器（模型/服务由部署方提供）


---

## 部署记录（2026-10-20）

| 项 | 值 |
|---|---|
| 代码版本 | `main` @ `3d6dda9`（P1/P2 全部批次已合并并推送） |
| 服务器 | `root@8.137.123.233`，部署目录 `/opt/echolint` |
| 访问地址 | http://8.137.123.233/ （HTTP/80；前端 nginx，后端仅 127.0.0.1:8082） |
| 管理员 | `admin`（密码在服务器 `/opt/echolint/.env` 的 `APP_ADMIN_PASSWORD`） |
| 数据库 | `gx-mysql` 内 `echolint` 库；Flyway baseline v1；预置 191 词 |
| AI | DeepSeek 已加密落库（ProviderRegistry）；阿里云百炼待配置 `DASHSCOPE_API_KEY` |
| DB 备份 | `/opt/echolint/backups/echolint-20261020-071045.sql.gz` |
| 镜像回滚 | `echolint-backend:rollback-20261020-071045`、`echolint-frontend:rollback-20261020-071045` |
| 验证 | 登录 200、匿名 API/上传 401、管理接口 200、容器 healthy、日志 0 ERROR、内存稳定（backend ~319MiB/512MiB） |

### 上线前仍需处理（P0/运维项，非 P1/P2 代码范围）

- **转写服务**：使用本机 whisper.cpp；需启动 `tools/whisper-server.sh --daemon` 并配置 `WHISPER_BASE_URL`/`WHISPER_API_KEY`，否则上传录音会转写失败（FAILED）。
- **HTTPS/域名**：当前 HTTP 明文、公网 80；建议域名 + 证书 + 反代，并把 `server.servlet.session.cookie.secure` 打开。
- **备份/监控**：本次为手工备份；建议 cron 每日 mysqldump + uploads 卷备份，接入 Prometheus 告警与日志轮转。
- **安全**：`APP_SECRET_KEY` 已配置，变更会导致已存供应商密文不可解密；管理员默认密码请首次登录后立即修改。

---

## Batch 6 · PostgreSQL 迁移 + RAG（✅ 已完成）

> 状态：RAG 后端、前端与管理接口已完成，真实端到端冒烟结果见文末「完成记录」；
> **MySQL → PostgreSQL 数据迁移已于 2026-10-22 生产切换时执行**（13 张表 / 281 行逐行校验一致，
> 见文末「部署记录（2026-10-22）」）。pgloader 因 MySQL 8.4 `caching_sha2_password` 不可用，
> 改用 `tools/migrate_mysql_to_pg.py`；手册中的 pgloader 步骤仍适用于旧认证插件环境。
> 相关文档：`docs/rag-integration.md`（用户向：架构/配置/API/索引/降级/评测）、
> `docs/rag-contract.md`（内部契约）、`docs/postgres-migration.md`（迁移/校验/回滚手册）。

### 目标

- 数据库从 **MySQL 8** 整体迁移到 **PostgreSQL 17 + pgvector**：不再依赖 MySQL 驱动/方言，
  为知识库语义检索提供向量能力。
- 接入 **RAG 检索增强**：知识库/SOP 分块向量化，复筛时按语义召回并注入提示词；
  检索结果在 AI 结论与调用留痕中可追溯；embedding 不可用时自动降级、不阻塞复筛。
- 保持默认关闭、零行为变化：`APP_RAG_ENABLED=false` 时复筛与旧版一致。

### 范围

**数据库与部署**

- compose 服务 `postgres`：镜像 `pgvector/pgvector:pg17`、容器 `echolint-postgres`、
  宿主 `127.0.0.1:5433 → 5432`、库 `echolint`、用户 `audit/audit123`、数据卷 `pg-data`；
  后端容器内 `SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/echolint`。
- 本地开发：启动 `docker compose up -d postgres`；后端默认
  `jdbc:postgresql://localhost:5432/echolint`，连容器用宿主 `5433`。
- Flyway `V2__postgres_pgvector_rag.sql`：`CREATE EXTENSION vector`、`knowledge_chunks`；
  `V3__rag_embedding_1024.sql`：把 HNSW 表达式索引调整为 **1024 维**（阿里云百炼 embedding）。
  业务表仍由 Hibernate `ddl-auto: update` 管理。
- 旧 MySQL 数据迁移：**先启动新版应用在空 PG 上建 schema，再用 pgloader/CSV 只搬数据（data only）**，
  不可让 pgloader 建表；行数/关键表抽样/时间字段/序列 `setval`/pgvector 扩展与索引逐项校验，
  并保留回滚到 MySQL 备份的路径（见 `docs/postgres-migration.md`）。

**RAG 功能（配置与行为契约见 `docs/rag-contract.md`）**

- 配置：`app.rag.*` / `APP_RAG_*` 全量变量（enabled、top-k、min-score、max-context-chars、
  corpus-examples、embedding.*、chunk.*、corpus.max-chars、index.*、documents.*），
  `.env.example` 与 `application.yml` 逐项核对一致，compose 已透传全部变量。
- 数据模型：`knowledge_chunks`（含 HNSW 索引）；`knowledge_entries` 新增来源/索引状态列；
  `corpus_entries` 新增 embedding 列；`ai_invocations.recording_id` 可空、新增 embedding stage。
- 知识库：写入后异步分块+向量化，失败置 `FAILED` 可重试/重建；支持 PDF/DOCX/TXT/MD 文档导入；
  新增语义检索接口。
- 复筛：知识库召回 + 语料语义 few-shot 注入 prompt；`recordings.aiResultJson.rag` 记录
  `enabled/status/references`；检索失败降级为「不注入」并 WARN，不阻塞复筛。
- 管理端：`/api/settings/rag` 读取/保存/连通性测试/重建索引/模型评测；前端设置页与知识库页。

**文档与工具**

- `README.md`：技术栈、快速开始、端口、本地开发、演示数据、环境变量、注意事项全部 PostgreSQL 化；
  RAG 小节补充功能概要与文档入口。
- `docs/rag-integration.md`（新增）：总体架构、pgvector 表/HNSW 表达式索引、全量配置、API、
  索引生命周期与失败恢复、降级行为、评测方法与解读、成本与隐私、已知限制、运维排障。
- `docs/postgres-migration.md`：迁移前检查、schema 初始化顺序、pgloader 命令、数据校验、pgvector 校验、
  回滚方案、常见坑。
- `.env.example`：数据库段 + RAG 全量变量（带默认值与中文注释）。
- `tools/seed_demo_data.py`、`tools/demo/make_demo.py`、`tools/demo/README.md`：psql 写库、PostgreSQL
  方言（布尔 `true/false`、`setval`、`INTERVAL`、标准字符串转义）。

### 验收清单（按实际状态勾选）

**构建与单测**

- [x] 后端最终全量回归：`mvn -B -q test` **EXIT=0**；surefire **75 classes / 526 tests /
      0 failures / 0 errors / 0 skipped**（Lead 实测；含 RAG controller/service/检索/文档抽取/分块新增测试，
      与本文本机 surefire 汇总一致）
- [x] 前端 `npm run build` + `npm test`：**120/120 通过**（前端 teammate 实测，收尾补文案后仍全绿）
- [ ] 后端打包 `mvn -q -B -DskipTests package`：本次最终回归只跑 `test`，未单独执行打包
- [ ] CI（`.github/workflows/ci.yml`）在分支上的运行结果未核对；CI 当前不依赖数据库

**数据库与迁移**

- [x] 全新空 PG 启动：Flyway V1/V2/V3 成功；`vector` 0.8.7、`knowledge_chunks`、HNSW 表达式索引（1024 维）存在；
      `ai_invocations.recording_id` 可空验证；应用启动 6.7s
- [x] 新版应用在空库完成 schema 初始化与主链路冒烟：上传 → 转写 → DFA → AI 复筛 → 知识库索引
- [ ] pgloader `data only` 端到端迁移：**未执行**
- [ ] 行数/关键表抽样/时间字段/序列 `setval` 校验：随迁移演练待做
- [ ] 迁移后（含旧数据）应用冒烟：待迁移演练
- [ ] 回滚到 MySQL 备份演练：未执行

**RAG**

- [x] 语料回填：8/8 条语料完成 embedding
- [x] 知识库写入异步索引：创建条目 → 异步分块 + 向量化 → `READY`
- [x] 文档导入（md 端到端）：解析 + 索引 `READY`；PDF/DOCX 抽取由单测覆盖（未做二进制端到端）
- [x] 语义检索预览：命中（示例 score 0.56）
- [x] `PUT /api/settings/rag` 保存并在运行时生效
- [x] 评测接口：返回 baseline / rag / delta / cases
- [x] 录音端到端注入：`aiResultJson.rag.status=ok` 且带 references；
      `ai_invocations` 的 SCREEN `request_json` 同时包含「知识库参考」与「人工复核参考案例」
- [x] embedding 留痕与成本：EMBED_INDEX 18 条、EMBED_QUERY 3 条（含 recording_id=1 的查询向量化）；
      成本/配额统计可聚合
- [ ] RAG 关闭回归（无 embedding 调用、与旧版一致）：单测覆盖，未单独做端到端冒烟
- [ ] embedding 不可用/超时降级：单测覆盖，未单独做端到端冒烟
- [ ] 全量重建索引接口（knowledge/corpus/all）：启动回填已验证，未单独冒烟全量重建接口
- [ ] 管理端 RAG 设置页/知识库索引状态：前端 build + 单测通过，未做浏览器端到端验收
- [ ] 文档导入失败分支（超限/魔数不符/重复 409）：单测覆盖，未做端到端

### 完成记录（2026-10-20，真实执行）

**环境与启动**

- PostgreSQL **17.11** + pgvector **0.8.7**（`pgvector/pgvector:pg17` 镜像）。
- Flyway V1 + V2 + V3 成功；`knowledge_chunks`、HNSW 表达式索引（1024 维）、`ai_invocations.recording_id` 可空均验证通过；
  应用启动 **6.7s**。
- RAG 冒烟使用**本地兼容模型桩**（embedding / chat / whisper 三个端点），未调用真实外部供应商。

**RAG 功能**

- 语料回填：8/8 条语料完成 embedding。
- 知识库：创建条目 → 异步分块 + 向量化 → `READY`。
- 文档导入：`md` 文件 → 解析 + 索引 `READY`（PDF/DOCX 抽取由单测覆盖）。
- 检索预览：命中，示例 `score=0.56`。
- 设置：`PUT /api/settings/rag` 生效。
- 评测：`POST /api/settings/rag/evaluate` 返回 baseline / rag / delta / cases。

**录音端到端**

- 上传 wav → stub 转写 → DFA 命中（傻逼 / 滚蛋）→ AI 复筛（stub 判定违规）。
- `recordings.aiResultJson` 含 `"rag":{"status":"ok","references":[...]}`。
- `ai_invocations` 中 SCREEN 的 `request_json` 同时包含「知识库参考」与「人工复核参考案例」两段。
- embedding 留痕：`EMBED_INDEX` 18 条、`EMBED_QUERY` 3 条（含 `recording_id=1` 的查询向量化）；
  成本/配额统计可聚合。

**测试**

- 后端：本机 surefire 汇总 **526 tests / 75 classes，0 failures / 0 errors**（含新增 RAG 测试）。
- 前端：**120/120 tests 通过 + `npm run build` 通过**（前端 teammate 实测；Lead 在收尾时又补了
  `Recordings.vue` 的 `EMBED_QUERY`/`EMBED_INDEX` 阶段文案与 `constants.js` 的 RAG 审计中文标签，补后仍全绿）。
- 最终回归：后端 `mvn -B -q test` **EXIT=0**，surefire **75 classes / 526 tests / 0 failures / 0 errors / 0 skipped**
  （Lead 实测，与本文记录一致）。

**文档与配置**

- 新增 `docs/rag-integration.md`；README RAG 小节与 `.env.example` 已同步。
- `.env.example` 的 19 个 `APP_RAG_*` 与 `application.yml` 逐项核对一致；`docker-compose.yml` 已透传全部 19 个变量。
- `docker-compose.yml` 本次补透传 `DASHSCOPE_BASE_URL` / `DEEPSEEK_BASE_URL`（此前在 `.env` 设置不会进容器），
  README 环境变量表与 `.env.example` 已注明。
- SQL 自检命令、维度变更重建索引步骤、API curl 示例见 `docs/rag-integration.md` §5/§10。


### 2026-10-21 国内化调整（embedding / 转写去境外依赖）

- **完全移除 OpenAI**：删除 `openai` 供应商配置、`OPENAI_*` 环境变量与 OpenAI 相关默认值；
  DeepSeek 作为复筛模型，转写固定本机 whisper.cpp。
- **RAG embedding 切换阿里云百炼**：供应商 `aliyun`（`https://dashscope.aliyuncs.com/compatible-mode/v1`），
  模型 `qwen3.7-text-embedding`，维度 **1024**；请求体显式发送 `dimensions=1024`。
- **Flyway V3**：HNSW 表达式索引由 1536 维调整为 1024 维，并清理/复位旧维度向量。
- 当前环境未配置 `DASHSCOPE_API_KEY`，RAG 保持关闭；配置密钥后打开开关并「全量重建」即可。
- 验证：后端全量单测通过；`GET /api/settings/rag` 显示 provider=aliyun / dimensions=1024；
  provider 列表中不再包含 OpenAI。


### 已知偏差与限制

- **MySQL → PostgreSQL 迁移已执行（2026-10-22 生产切换）**：`tools/migrate_mysql_to_pg.py` 搬运
  13 张表 / 281 行，逐行逐微秒比对一致；pgloader 因 MySQL 8.4 `caching_sha2_password` 不可用
  （见 `docs/postgres-migration.md` §0.2）；回滚资产（旧库 dump、rollback 镜像、旧 compose/.env）已保留。
- **全新空库首次启动需先关一次 Flyway**：V3 在 Hibernate 建表前更新业务表，空库会启动失败；
  绕过步骤见 `docs/postgres-migration.md` §0.1（不改已发布的迁移文件，避免 checksum 不一致）。
- **Flyway 10.10 对 PostgreSQL 17 仅版本告警**，迁移（V1/V2/V3）可正常执行，不影响功能。
- **HNSW 维度写死在 V3（1024）**：更换 embedding 模型/维度需按 `docs/rag-integration.md` §5.4 清空向量、
  重建索引；新维度必须与 `APP_RAG_EMBEDDING_DIMENSIONS` 一致。
- **embedding 供应商/模型/维度来自配置**：修改需重启；设置页只能热改开关与检索参数（topK/minScore 等）。
- **导入文档原文不自动清理**：删除知识库条目只删数据库记录与分块，`knowledge-docs` 卷中的文件保留。
- **向量规模上限**：pgvector HNSW 单机索引，无分区/分片；知识库分块规模大时需评估内存、构建时间与召回率；
  检索为纯向量召回，没有混合检索/rerank/query rewrite。
- **进程内索引状态**：异步线程池与同条目互斥是单实例语义；多实例部署时启动回填可能重复消耗 embedding
  （结果幂等，但会重复计费），重索引没有分布式锁。
- **RAG 未做多租户隔离**：沿用当前单租户模型。
- **管理端页面未做浏览器端到端验收**：前端 build 与单测通过，接口已实测，页面交互待前端最终确认。
- **验收缺口**：RAG 关闭回归、embedding 降级、全量重建索引、文档导入失败分支目前由单测覆盖，
  未单独做端到端冒烟（见上方未勾选项）。

---

## 部署记录（2026-10-22，MySQL → PostgreSQL 正式切换）

| 项 | 值 |
|---|---|
| 代码版本 | `main` @ `9904bbc`（PostgreSQL/pgvector + RAG + 浅色分层界面） |
| 部署时间 | 2026-10-22 23:00–23:20（Asia/Shanghai），停机约 20 分钟 |
| 服务器 | `root@8.137.123.233`，部署目录 `/opt/echolint` |
| 访问地址 | http://8.137.123.233/ （HTTP/80，nginx 1.31；前端 gzip + `/assets/` immutable 缓存） |
| 后端 | 仅 `127.0.0.1:8082`；健康检查 `/actuator/health` = `{"status":"UP"}` |
| 数据库 | PostgreSQL 17.11 + pgvector 0.8.7；容器 `echolint-postgres`，库/用户 `echolint`/`audit`，仅绑 `127.0.0.1:5433`，卷 `echolint_pg-data` |
| 迁移数据 | 13 张表 / 281 行（录音 9、流水线日志 37、审计 12、词库 191、知识 3、语料 8、AI 留痕 7、供应商 2、任务 8、复核 3、管理员 1）；逐行逐微秒校验一致 |
| 上传文件 | 沿用旧卷 `echolint_uploads`，9 条录音详情页音频 200 可播；新增 `echolint_knowledge-docs` 卷 |
| 旧库备份 | `/opt/echolint/backups/echolint-mysql-20261022-230331.sql.gz`（16K，含 13 张表） |
| 回滚点 | 镜像 `echolint-backend:rollback-20261022-230331`、`echolint-frontend:rollback-20261022-230331`；旧 compose/.env/DEPLOY.md 在 `backups/*.mysql-20261022-230331*` |
| 验证 | 端到端冒烟 18/18：登录/登出、会话、录音列表 9、详情+音频、知识库 3、AI 留痕 7、审计、统计、RAG 设置、供应商列表；匿名 API/上传 401；重启后数据与 Flyway 历史不变；日志无 ERROR |

### 本次部署暴露的问题（已在 `docs/postgres-migration.md` §0 记录）

- **全新空库首次启动 Flyway V3 失败**：`V3__rag_embedding_1024.sql` 在 Hibernate 建表前 `UPDATE`
  业务表 → `relation "knowledge_entries" does not exist`，应用启动即崩。处理：首次启动临时
  `SPRING_FLYWAY_ENABLED=false` 建表，再恢复 Flyway 应用 V3（V1/V2/V3 全部 success）；未改动迁移文件。
- **pgloader 不支持 MySQL 8.4 认证**：QMynd 报 `MYSQL-UNSUPPORTED-AUTHENTICATION`，改用
  `tools/migrate_mysql_to_pg.py`（pymysql + psycopg2，281 行秒级搬完并做行数校验）。

### 上线后仍未处理（沿用旧状态，非本次部署范围）

- **转写未配置**：服务器无 whisper 服务，`WHISPER_BASE_URL` 指向不存在的 host.docker.internal:9900，
  上传录音会转写失败（FAILED）；需自建 whisper.cpp 或配置兼容 ASR。
- **RAG 未启用**：未配置 `DASHSCOPE_API_KEY`，`APP_RAG_ENABLED=false`；配密钥后可在设置页开启并全量重建索引。
- **HTTPS/域名**：仍为 HTTP 明文公网 80。
- **Flyway 10.10 对 PG 17 仅版本告警**（官方支持到 16），本次迁移正常执行，不影响功能。
- **备份/监控**：仍为手工备份；建议 cron 每日 `pg_dump` + uploads 卷备份。
