# 集成适配器（多租户 / SSO / 流式 ASR / 说话人分离）

> Batch 5 目标：把需要**外部选型/部署**的能力做成「可插拔接口 + 配置 + 状态 API + 接入文档」。
> **默认全部关闭，零行为变化**；选型确定后只需实现适配器，不改主流程。
> 本文是四类集成的配置项、接口契约、接入步骤、安全与回滚说明。

## 1. 设计原则

| 原则 | 说明 |
|---|---|
| 默认关闭 | 所有 `app.*.enabled` 默认 `false`，关闭时与 Batch 4a 的行为完全一致 |
| 接口先行 | 外部依赖能力只暴露接口（`TenantResolver` / `SsoProvider` / `StreamingTranscriptionProvider` / `DiarizationProvider`），默认实现均为安全 no-op |
| 密钥只走配置 | `client-secret`、`api-key` 只从环境变量/配置文件注入；任何 API、日志、审计都**不返回、不打印** |
| 状态可观测 | ADMIN 可在系统设置查看各集成「是否启用/是否已配置/供应商/登录地址」；登录页可匿名读取 SSO 公开配置 |
| 失败可降级 | 默认实现显式抛「未配置」，调用方必须捕获并降级，不允许静默产生错误结果 |
| 版本可回滚 | 关开关即恢复旧行为；新增能力不改变现有表结构与流水线 |

---

## 2. 状态接口

### 2.1 管理端：`GET /api/settings/integrations`（ADMIN）

```json
{
  "tenant":      { "enabled": false, "mode": "SINGLE", "provider": "single", "configured": true },
  "sso":         { "enabled": false, "provider": "OIDC", "configured": false, "loginUrl": "" },
  "streaming":   { "enabled": false, "provider": "unsupported", "configured": false },
  "diarization": { "enabled": false, "provider": "unsupported", "configured": false }
}
```

- `configured` 表示**必要配置是否齐全**（与 `enabled` 解耦，便于区分「已配置但未启用」）。
- `sso.loginUrl` 由当前 `SsoProvider` 适配器提供；空串表示「服务端配置了但客户端适配器尚未实现」，前端不得展示可点击的 SSO 入口。
- 该接口与本节所有接口**不含任何密钥/端点密钥参数**。

### 2.2 登录页：`GET /api/auth/sso/config`（匿名 permitAll）

```json
{ "enabled": true, "provider": "OIDC", "configured": true, "loginUrl": "https://idp.example.com/authorize" }
```

命名与结构同 `sso` 子对象；只暴露前端展示所需的最小信息。

> HEADER 租户模式开启时，该公开接口同样要求 `X-Tenant-Id`（过滤器作用于全部 `/api/**`）。
> 登录页/网关需先确定租户（如子域、路径前缀）再请求该接口。

---

## 3. 多租户（tenant 解析适配器）

### 3.1 配置

| 配置键 | 环境变量 | 默认 | 说明 |
|---|---|---|---|
| `app.tenant.enabled` | `APP_TENANT_ENABLED` | `false` | 总开关；false 时强制单租户 |
| `app.tenant.mode` | `APP_TENANT_MODE` | `SINGLE` | `SINGLE` / `HEADER` |
| `app.tenant.header` | `APP_TENANT_HEADER` | `X-Tenant-Id` | HEADER 模式读取的请求头 |

### 3.2 契约

```java
public interface TenantResolver {
    String resolve(jakarta.servlet.http.HttpServletRequest request); // 解析失败抛 BizException(400)
}
```

- `SingleTenantResolver`（默认）：固定返回 `default`。
- `HeaderTenantResolver`：读取配置的请求头；去首尾空白后必须匹配 `[A-Za-z0-9][A-Za-z0-9_-]{0,63}`
  （1~64 位字母/数字/下划线/中划线）。
- `TenantContext`：ThreadLocal，`TenantContext.current()` 读取；由 `TenantFilter` 在请求进入时设置、`finally` 中清理（异常路径也清理）。
- `TenantFilter`：URL 限定 `/api/*`，注册在 Spring Security **之前**（order = `SecurityProperties.DEFAULT_FILTER_ORDER - 10`），
  因此 HEADER 模式下缺失/非法租户头稳定返回 JSON 400，与是否登录无关；`OPTIONS` 预检直接放行。
- 校验失败响应（与全局异常处理格式一致）：

```json
{ "code": 400, "message": "缺少租户标识请求头: X-Tenant-Id", "timestamp": "2026-10-20T12:00:00" }
```

- **本轮不做数据隔离**：`TenantContext` 只提供请求级标签，不参与任何查询条件。

### 3.3 数据隔离分阶段方案（后续批次）

> ⚠️ 在完成以下步骤前，多租户只是「请求标签」，不能当作安全边界；跨租户数据仍互相可见。

1. **加列与索引（V2 迁移，向前兼容）**
   ```sql
   ALTER TABLE recordings ADD COLUMN tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';
   ALTER TABLE pipeline_tasks ADD COLUMN tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';
   -- 其它业务表同理（corpus_entries / review_records / audit_logs / dictionary_words / knowledge_entries / api_keys）
   CREATE INDEX idx_recordings_tenant_status_id ON recordings (tenant_id, status, id);
   ```
   历史数据回填 `default`；上线新列后旧版本代码仍可运行（列有默认值）。
2. **写入侧**：上传/任务/复核/审计写入时取 `TenantContext`（缺失回退 `default`），并加入实体与 DTO。
3. **查询侧**：所有 repository 查询统一追加 `tenant_id = :tenant`（推荐在 `RecordingSpecifications` 等入口集中处理，
   并新增跨租户管理员视角开关）；任何按 id 的直接读取（详情/删除/重试）都必须带租户条件，避免 IDOR。
4. **约束**：唯一键改复合（如 `(tenant_id, audio_sha256)`、`(tenant_id, word)`），上传目录按租户分目录。
5. **认证**：用户与租户成员关系（`app_user_tenant` 表）替代「信任请求头」；HEADER 模式仅用于网关已鉴权的内网场景。
6. **回滚**：关闭 `app.tenant.enabled` 即回到单租户口径；新列保留（默认 `default`）不影响旧逻辑；
   V2 迁移为加列/加索引的可逆操作，回滚脚本仅需删除索引/列（先确认无代码依赖）。

### 3.4 安全注意

- 不要在没有网关/认证的情况下信任客户端自带的租户头（可伪造）。生产建议：网关注入租户头并校验；
  或改为从登录态（用户-租户关系）解析，而不是请求头。
- 错误信息不回显原始请求头值（防日志/响应注入）。
- 租户 ID 为纯标识，不含密钥；`TenantContext` 不跨异步线程传播（本轮无隔离需求；做隔离时需在任务入队时持久化 `tenant_id`）。

---

## 4. SSO / LDAP（身份源适配器）

### 4.1 配置

| 配置键 | 环境变量 | 默认 | 说明 |
|---|---|---|---|
| `app.sso.enabled` | `APP_SSO_ENABLED` | `false` | 总开关；关闭时仍用本地账号密码登录 |
| `app.sso.provider` | `APP_SSO_PROVIDER` | `OIDC` | `OIDC` / `LDAP` |
| `app.sso.issuer` | `APP_SSO_ISSUER` | (空) | OIDC issuer；LDAP 为 `ldap(s)://host:port` |
| `app.sso.client-id` | `APP_SSO_CLIENT_ID` | (空) | OIDC client_id；LDAP 为绑定 DN |
| `app.sso.client-secret` | `APP_SSO_CLIENT_SECRET` | (空) | OIDC client_secret；LDAP 为绑定密码（**仅配置，不返回**） |
| `app.sso.role-mapping-json` | `APP_SSO_ROLE_MAPPING_JSON` | (空) | IdP 组 → 本地角色 JSON，见下 |
| `app.sso.role-mapping` | `APP_SSO_ROLE_MAPPING` | (空) | 兼容旧键；与 `-json` 同时配置时以 `-json` 为准 |

`configured = issuer && client-id && client-secret 均非空`（不判断 `enabled`）。

角色映射示例：

```json
{ "echolint-admins": "ADMIN", "echolint-reviewers": "REVIEWER", "echolint-viewers": "VIEWER" }
```

**建议默认拒绝**：IdP 组不在映射表内时拒绝登录（或按最小权限 `VIEWER`），不要默认给 ADMIN。

### 4.2 契约

```java
public interface SsoProvider {
    String id();                    // oidc / ldap / disabled
    boolean enabled();
    boolean configured();
    String loginUrl();              // 登录页跳转地址；未实现返回 ""
    Map<String, Object> metadata(); // 附加信息，禁止放密钥
}
```

- 默认 Bean：`DisabledSsoProvider`（`enabled=false`、`loginUrl=""`），直接注入即可使用。
- 接入真实身份源：实现 `SsoProvider` 并**覆盖/替换** `IntegrationConfig#ssoProvider` Bean（或将实现类标注 `@Primary`）。
- `GET /api/auth/sso/config` 与集成状态接口会自动使用新适配器的 `loginUrl()`；`enabled/configured` 仍由配置决定。

### 4.3 OIDC 接入步骤

1. **加依赖**（未来批次，本轮刻意不加）：
   `spring-boot-starter-oauth2-client`（或直接用 `spring-security-oauth2-client` + `spring-security-oauth2-jose`）。
2. **IdP 注册应用**：重定向 URI 例：`https://echolint.example.com/login/oauth2/code/echolint`；
   scopes：`openid profile email`（如需要组信息，再加 `groups` 或 IdP 自定义 scope）。
3. **配置**：实现 `SsoProvider`，`loginUrl` 指向 IdP 授权端点（带 `client_id`、`redirect_uri`、`response_type=code`、`scope`、`state`、`nonce`、PKCE `code_challenge`）。
   Spring Security 可用 `OAuth2AuthorizationRequestResolver` 自动生成 state/nonce/PKCE，不必手拼 URL。
4. **回调与登录态**：回调成功后校验 `state`/`nonce`（框架默认），取 `sub`/`email`/`groups`；
   映射到本地 `AppUser`（不存在则按策略拒绝或 JIT 创建，创建时默认 `VIEWER` + 禁用需管理员启用）；
   复用现有会话写入逻辑（`HttpSessionSecurityContextRepository`），最终与本地登录一致。
5. **角色映射**：用 `app.sso.role-mapping-json` 把 IdP 组映射为 `ADMIN/REVIEWER/VIEWER`，未知组拒绝登录。
6. **登出**：本地 `POST /api/auth/logout` 已清会话；SSO 场景需再跳 IdP `end_session_endpoint`
   （`id_token_hint` + `post_logout_redirect_uri`），避免 IdP 侧会话仍在。
7. **安全**：校验 `iss`/`aud`/`exp`/签名（JWKS）；禁止跳过 TLS 校验；client-secret 仅服务端持有；回调地址白名单固定。

### 4.4 LDAP / Active Directory 接入步骤

1. **加依赖**（未来批次）：`spring-security-ldap`（如需简化可加 `spring-boot-starter-data-ldap`）。
2. **配置**：`issuer` 作目录地址（优先 `ldaps://`），`client-id` 作绑定 DN，`client-secret` 作绑定密码；
   另需在适配器实现里配置 BaseDN / 用户搜索过滤器（如 `(sAMAccountName={0})`）/ 组属性（`memberOf`）。
3. **认证流程**：先用服务账号绑定搜索用户 DN → 用用户 DN + 用户密码二次绑定验证 → 读取组 → 角色映射 → 写会话。
4. **安全**：强制 `ldaps` 或 StartTLS；绑定账号只读、最小权限；限制搜索范围防目录遍历；连接池 + 超时；密码不落日志。
5. **降级**：LDAP 不可用时返回明确错误并允许回退本地管理员账号登录（保留一个本地 ADMIN 兜底，避免锁死）。

### 4.5 回滚

- `APP_SSO_ENABLED=false` 即恢复本地账号登录；`/api/auth/sso/config` 返回 `enabled=false`，登录页隐藏 SSO 入口。
- 移除/停用自定义 `SsoProvider` Bean 后回到 `DisabledSsoProvider`；务必保留至少一个可用的本地 ADMIN 账号。

---

## 5. 实时（流式）ASR 适配器

### 5.1 配置

| 配置键 | 环境变量 | 默认 | 说明 |
|---|---|---|---|
| `app.asr.streaming.enabled` | `APP_ASR_STREAMING_ENABLED` | `false` | 总开关 |
| `app.asr.streaming.provider` | `APP_ASR_STREAMING_PROVIDER` | (空) | 供应商标识（funasr/deepgram/…），仅展示与选型 |
| `app.asr.streaming.endpoint` | `APP_ASR_STREAMING_ENDPOINT` | (空) | 流式网关地址（`wss://`/`https://`，协议由适配器约定） |
| `app.asr.streaming.api-key` | `APP_ASR_STREAMING_API_KEY` | (空) | 密钥（**仅配置，不返回**） |

`configured = endpoint && api-key 均非空`（不判断 `enabled`）。

### 5.2 契约

```java
public interface StreamingTranscriptionProvider {
    String id();
    boolean configured();
    StreamSession start(StreamOptions options);   // 未启用/未配置抛 BizException

    record StreamOptions(String language, Integer sampleRate) {}

    interface StreamSession extends AutoCloseable {
        void send(byte[] audioChunk); // 建议 16bit PCM / 16kHz，具体由适配器约定
        String finish();              // 结束并返回最终文本（可空串，不得 null）
        void close();                 // 幂等释放连接
    }
}
```

- 默认 Bean：`UnsupportedStreamingProvider`，`start()` 显式抛「流式转写未启用」。
- 接入真实供应商：实现接口并替换 `IntegrationConfig#streamingTranscriptionProvider` Bean。

### 5.3 接入点（不改现有批量流程）

现有批量链路（上传 → 持久任务队列 → Whisper 转写 → DFA → AI）保持不变。流式能力是**增量入口**：

1. **连接层**：新增 WebSocket 端点（如 `/ws/streaming/transcribe`）或 SSE 端点（如 `/api/streaming/transcribe`）。
   - WebSocket 握手是 HTTP GET，会经过现有安全过滤器链：需要会话登录（未登录 401）；
     租户过滤器对 `/api/**` 生效，若走 `/ws/**` 需自行在握手阶段解析租户。
   - 建议先落地 SSE（普通 HTTP，鉴权/租户/审计语义与现有接口一致），再评估 WebSocket 双向性。
2. **会话生命周期**：握手建立 → `provider.start(StreamOptions)` → 前端按 100~500ms 分片 `send()` →
   结束时 `finish()` 拿最终文本 → `close()`；异常/超时必须 `close()`，避免连接泄漏。
3. **结果落库**：实时文本可用于「边录边看」，是否落库由产品决定。若需要进入稽核流水线，
   建议把最终音频+文本写成一条 `recordings`（复用 `RecordingService.upload` 的去重/任务队列），
   或新增「流式来源」字段（需后续迁移），**不要**在流式回调里直接改现有批量流水线状态。
4. **背压与限制**：单会话并发上限、每会话最大时长/音频字节数、发送频率限制；慢客户端丢弃策略要显式记录。
5. **可观测**：指标（会话数/时长/错误码）、审计（谁发起了实时转写）；日志不打印音频内容与密钥。

### 5.4 回滚

- `APP_ASR_STREAMING_ENABLED=false`：状态接口显示 `enabled=false`，前端隐藏实时入口；
  任何误调用 `start()` 都抛「未启用」，不会影响批量转写。
- 移除自定义 Provider Bean 即回到 `UnsupportedStreamingProvider`。

---

## 6. 说话人分离（diarization）适配器

### 6.1 配置

| 配置键 | 环境变量 | 默认 | 说明 |
|---|---|---|---|
| `app.diarization.enabled` | `APP_DIARIZATION_ENABLED` | `false` | 总开关 |
| `app.diarization.provider` | `APP_DIARIZATION_PROVIDER` | (空) | 供应商标识（pyannote/aliyun/…），仅展示与选型 |
| `app.diarization.endpoint` | `APP_DIARIZATION_ENDPOINT` | (空) | 分离服务地址（`http(s)://`） |
| `app.diarization.api-key` | `APP_DIARIZATION_API_KEY` | (空) | 密钥（**仅配置，不返回**） |

`configured = endpoint && api-key 均非空`（不判断 `enabled`）。

### 6.2 契约

```java
public interface DiarizationProvider {
    String id();
    boolean configured();
    List<SpeakerSegment> diarize(java.nio.file.Path audioPath); // 本地文件路径，禁止外部 URL
}

public record SpeakerSegment(String speaker, double startSeconds, double endSeconds) {}
```

- 默认 Bean：`UnsupportedDiarizationProvider`，`diarize()` 显式抛「说话人分离未启用」。
- 仅单声道录音需要；**双声道仍按现有左右声道拆分方案**（左=坐席、右=客户），不调用 diarization。

### 6.3 合并与降级策略

1. **时机**：批量转写完成后、DFA 初筛前（拿到 `segmentsJson` 与音频路径后）。
2. **输入**：仅当 `channelCount == 1`（或分轨失败退化为单声道）且 `app.diarization.enabled=true` 时调用；
   传入服务器本地上传目录下的文件路径，禁止把 URL 透传给外部服务（防 SSRF）。
3. **合并**：对每条转写分段，按「时间区间重叠度最大」匹配 `SpeakerSegment`，把说话人标签写进
   `segments[].speaker`（保留原 `channel` 字段以兼容前端双栏逻辑）。
   重叠度为 0（静音/边界抖动）时保持原转写说话人，不强行分配。
4. **命名**：适配器返回 `SPEAKER_00` 等标签；展示层可映射为「说话人 1/2」，或后续增加配置映射。
5. **降级**：
   - provider 未配置/超时/返回空/格式非法 → `log.warn` + 整条录音按「单说话人」继续，**不得让流水线失败**；
   - 分离耗时纳入单条录音处理超时预算，超时即放弃（`close`/取消请求）；
   - 失败原因写入 `pipeline_logs`（WARN），便于回溯。
6. **缓存/成本**：结果可按音频 SHA-256 缓存（同一文件重跑不重复调用）；注意 Diarization 通常按音频时长计费。
7. **合并不改状态机**：分离只影响 `segments[].speaker`，不改变录音状态、DFA 命中、AI 结论。

### 6.4 回滚

- `APP_DIARIZATION_ENABLED=false` 即恢复「单说话人」行为；已写入的 `speaker` 字段保持原样（历史数据兼容）。
- 移除自定义 Provider Bean 即回到 `UnsupportedDiarizationProvider`；流水线不依赖该适配器可用。

---

## 7. 安全清单与回滚总表

| 事项 | 要求 |
|---|---|
| 密钥 | `client-secret`、`api-key` 只从环境变量/配置注入；不出现在任何 API 响应、日志、审计、异常信息中 |
| 状态接口 | ADMIN：`/api/settings/integrations`；公开：`/api/auth/sso/config`（只含 4 个非敏感字段） |
| 默认安全 | 默认关闭 + no-op 适配器；未配置时调用显式抛错，不静默降级成错误结果 |
| 租户头 | 生产必须由网关/认证链路校验或改为登录态推导；单靠客户端请求头不能作为安全边界 |
| 外部 URL | 所有 endpoint 由部署方配置；运行时不接受用户输入的外部 URL（防 SSRF） |
| 日志 | 租户 ID 可记录（已校验），不记录原始请求头、音频内容、密钥、完整 IdP 令牌 |

回滚总表：

| 集成 | 关闭方式 | 恢复后行为 |
|---|---|---|
| 多租户 | `APP_TENANT_ENABLED=false` | 固定 `default`，不校验租户头，与旧版一致 |
| SSO | `APP_SSO_ENABLED=false` | 仅本地账号登录，SSO 入口隐藏 |
| 流式 ASR | `APP_ASR_STREAMING_ENABLED=false` | 批量转写不受影响，实时入口不可用 |
| 说话人分离 | `APP_DIARIZATION_ENABLED=false` | 单说话人输出；双声道逻辑不受影响 |

> 所有开关变更均只需重启后端（配置类为 `@ConfigurationProperties`，不做运行时热更新）。

## 8. 相关代码

| 能力 | 接口 | 默认实现 | 配置类 | 装配 |
|---|---|---|---|---|
| 多租户 | `integration/tenant/TenantResolver` | `SingleTenantResolver` | `TenantProperties` | `IntegrationConfig` + `TenantFilter` |
| SSO/LDAP | `integration/sso/SsoProvider` | `DisabledSsoProvider` | `SsoProperties` | `IntegrationConfig#ssoProvider` |
| 流式 ASR | `integration/streaming/StreamingTranscriptionProvider` | `UnsupportedStreamingProvider` | `StreamingProperties` | `IntegrationConfig#streamingTranscriptionProvider` |
| 说话人分离 | `integration/diarization/DiarizationProvider` | `UnsupportedDiarizationProvider` | `DiarizationProperties` | `IntegrationConfig#diarizationProvider` |
| 状态汇总 | `service/IntegrationStatusService` | — | — | `SettingsController` / `AuthController` |
