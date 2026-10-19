# EchoLint 生产化补齐计划（P1 / P2）

> 目标：把当前「功能闭环的内测版」补到可对外上线的产品。
> 原则：分批交付；每批必须 `mvn test`、`npm run build` / `npm test` 通过并单独提交；
> 依赖外部选型的 P2 项先落地适配器与接口，选型确定后只需实现适配器。
> 进度以本文件的 checkbox 为准。

## 状态总览

| 批次 | 内容 | 状态 |
|---|---|---|
| 1 | 身份/权限/复核工作流/操作审计（原 P0-1 作为 P1 前置） | ✅ 已完成（2026-10-20） |
| 2 | 录音元数据与筛选、报表导出、词库运营、合规抽样、通知告警、可观测性、工程化 | 待开始 |
| 3 | AI 治理（供应商/密钥/配额/成本/重试）、异步任务持久化、成本看板 | 待开始 |
| 4 | P2 可独立项：i18n、移动端、无障碍、大屏、开放 API/Webhook、知识库/SOP、前端性能、分片上传 | 待开始 |
| 5 | P2 外部依赖项：多租户、SSO/LDAP、实时转写、单声道说话人分离（适配器先行） | 待开始 |

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

- [ ] 录音元数据：坐席、团队、通话时间、方向、业务线（上传时可填/可导入），列表支持日期范围/坐席/团队筛选
- [ ] 报表导出：录音清单、违规明细、复检记录（CSV/Excel），按坐席/团队/违规类型/日期统计
- [ ] 敏感词批量导入/导出（CSV/Excel）、批量启停、变更审计
- [ ] 合规抽样：DFA 未命中的录音按比例进入 AI/人工抽检（可配置）
- [ ] 通知告警：Webhook（企微/钉钉/自定义），高违规、待复核积压、处理失败
- [ ] 可观测性：Micrometer + Prometheus、结构化日志、异常脱敏（不再回显 e.getMessage()）、Docker 日志轮转
- [ ] 工程化：分页上限、OpenAPI（springdoc）、CI（GitHub Actions）、Flyway 基线迁移

## Batch 3 · AI 治理与异步可靠性

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

## Batch 5 · P2 外部依赖项（适配器先行）

- [ ] 多租户：租户模型、数据隔离、租户管理
- [ ] SSO/LDAP：OIDC/SAML 适配器（IdP 由部署方提供）
- [ ] 实时转写：流式 ASR 适配器（供应商由部署方提供）
- [ ] 单声道说话人分离：外部 diarization 适配器（模型/服务由部署方提供）
