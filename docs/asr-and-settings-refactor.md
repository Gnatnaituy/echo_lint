# ASR Provider 切换 + 系统设置页重构（实施说明）

> **状态：已实现并上线（2026-11-10）**。本文保留设计、API 契约、三轮上线与评审修复记录；
> 生产当前生效的 provider 以系统设置页为准（本部署已显式切到阿里云百炼）。

> 目标读者：本次并行开发的后端 / 前端同学。
> 契约由 Team Lead 定稿，后端与前端按本文件实现即可，不需要互相等代码。

## 0. 背景与目标

1. **转写选型**：从「固定本地 whisper.cpp」改为「本地 whisper.cpp + 阿里云百炼（DashScope）双 provider」。
   - 生产经设置页显式切换到阿里云百炼；代码默认 provider 为本地 whisper.cpp（避免「只配了 Key 就被隐式切云」），
     本地 whisper.cpp 方案**完整保留**，随时可切回。
   - 切换在「系统设置」页完成，**立即生效、重启保持**（沿用 AI 复筛模型的运行时切换范式）。
2. **系统设置页重构**：现在 `/settings` 把 7 张卡片竖着堆在一起，交互与信息层级混乱。
   拆成「复筛模型 / 语音转写 / 知识增强(RAG) / 通知与抽样 / 运行环境」五个子页面 + 二级导航，
   每页只讲一件事，并新增「语音转写」页面承载 provider 切换。

## 1. 已完成的技术验证（2026-11-10 实测，可直接照做）

DashScope 录音文件识别全流程，**不需要公网文件 URL，也不需要用户自己的 OSS 桶**：

```
1) 上传凭证  GET  {asrBase}/api/v1/uploads?action=getPolicy&model={model}
              Header: Authorization: Bearer {apiKey}
   → data{ policy, signature, upload_host, upload_dir, oss_access_key_id,
           x_oss_object_acl, x_oss_forbid_overwrite, max_file_size_mb }

2) 上传文件  POST {upload_host}   （multipart/form-data）
   字段：OSSAccessKeyId / Signature / policy / key={upload_dir}/{文件名带扩展名}
        success_action_status=200 / x-oss-object-acl / x-oss-forbid-overwrite / file
   → HTTP 200，对象 key 即 {upload_dir}/{文件名}

3) 提交任务  POST {asrBase}/api/v1/services/audio/asr/transcription
   Header: Authorization: Bearer {apiKey}
           Content-Type: application/json
           X-DashScope-Async: enable
           X-DashScope-OssResourceResolve: enable        ← 关键，允许 file_urls 用 oss://
   Body:   { "model": "qwen-audio-3.1-asr-flash-filetrans",
             "input": { "file_urls": ["oss://{key}"] },
             "parameters": { "channel_id": [0], "language_hints": ["zh"],
                             "diarization_enabled": false } }
   → output.task_id / output.task_status="PENDING"

4) 轮询      GET  {asrBase}/api/v1/tasks/{task_id}
   → output.task_status ∈ PENDING / RUNNING / SUCCEEDED / FAILED
     成功：output.results[0].transcription_url
     失败：output.results[0].code / message

5) 取结果    GET  {transcription_url}（预签名 URL，**不要附加 Authorization**）
   JSON：
   {
     "properties": { "original_duration_in_milliseconds": 26119, "channels": [0] },
     "transcripts": [{
       "channel_id": 0,
       "text": "整段文本",
       "sentences": [ { "begin_time": 440, "end_time": 4720, "text": "…",
                        "sentence_id": 1, "words": [...] } ]   // speaker_id 仅开启说话人分离时出现
     }]
   }
```

实测结论（26.119s 中文 demo）：提交后约 2 秒完成；句级时间戳为毫秒；文本精度与本地 whisper 一致。

注意事项（写进代码注释 / 页面说明）：

- 官方标注临时上传接口「限流 100 QPS，请勿用于生产高并发」；本项目量级可接受，未来量大可换自有 OSS。
- 临时文件 48h 过期、结果 JSON 链接 24h 过期；本流程即时消费，不受影响。
- `asrBase` 默认 `https://dashscope.aliyuncs.com`；也可填业务空间域名
  `https://{WorkspaceId}.cn-beijing.maas.aliyuncs.com`（路径不变）。
- 模型候选：`qwen-audio-3.1-asr-flash-filetrans`（默认，支持方言/热词/说话人分离）、
  `qwen-audio-3.0-asr-flash-filetrans`；允许手填。
- 计费：3.1 按 token（约 ¥0.8/M 输入），3.0 按语音时长（约 ¥0.00022/秒）。

## 2. 后端契约（backend/）

### 2.1 持久化（复用 `app_settings`，无需新迁移）

| 键 | 说明 | 未落库时回退 |
|---|---|---|
| `asr.provider` | `local` \| `dashscope` | **默认 `local`**；只有显式切换才改变（不会因为后来配了百炼 Key 就自动切云） |
| `asr.local.base-url` | whisper.cpp OpenAI 兼容地址 | `WHISPER_BASE_URL`，再默认 `http://127.0.0.1:9900` |
| `asr.local.api-key` | 加密（`SecretCipher`） | `WHISPER_API_KEY`，再默认 `local` |
| `asr.local.model` | 请求体的 model 字段 | `WHISPER_MODEL`，再默认 `whisper-1` |
| `asr.dashscope.base-url` | DashScope API 根（**不是** compatible-mode 地址） | `DASHSCOPE_ASR_BASE_URL`，再默认 `https://dashscope.aliyuncs.com` |
| `asr.dashscope.api-key` | 加密（`SecretCipher`） | `DASHSCOPE_API_KEY` |
| `asr.dashscope.model` | 录音文件识别模型名 | `DASHSCOPE_ASR_MODEL`，再默认 `qwen-audio-3.1-asr-flash-filetrans` |
| `asr.dashscope.language-hints` | 逗号分隔语言代码 | `DASHSCOPE_ASR_LANGUAGE_HINTS`，再默认 `zh` |
| `asr.timeout-seconds` | 单次转写总预算（本地/云端统一） | 默认 600，范围 30–1800 |

规则：

- DB 优先、env 兜底；密钥解密失败按「未配置」处理并 WARN，不影响启动。
- 视图只回掩码（复用 `SecretCipher.mask`）与 `apiKeySource`（`env` / `db` / `null`），**绝不回明文/密文**。
- PUT 时 `apiKey` 为空字符串 = 保持原值（沿用 provider 更新的既有约定）。
- 切换 active 时目标 provider 未就绪 → 400，错误信息说明缺什么（例如「百炼未配置 API Key」）。
- 每次保存/切换写审计：`AuditAction.SETTINGS_UPDATE`，resourceType=`SETTINGS`，resourceKey=`asr`。

### 2.2 REST 契约（全部仅 ADMIN；GET 也 ADMIN，因为含掩码配置）

> 权限：`SecurityConfig` 增加 `GET /api/settings/asr` → `hasRole("ADMIN")`；写操作已被
> `PUT/POST /api/settings/**` 规则覆盖。`SecurityMatrixTest` 的规则表同步补一条。

```
GET /api/settings/asr
200 {
  "activeProvider": "dashscope",
  "timeoutSeconds": 600,
  "local": {
    "label": "本地 whisper.cpp",
    "ready": true,
    "baseUrl": "http://host.docker.internal:9900",
    "model": "whisper-1",
    "apiKeyConfigured": true,
    "apiKeyMasked": "****ocal",
    "apiKeySource": "env",
    "audioPath": "/v1/audio/transcriptions",
    "envHints": { "baseUrl": "WHISPER_BASE_URL", "apiKey": "WHISPER_API_KEY" }
  },
  "dashscope": {
    "label": "阿里云百炼",
    "ready": true,
    "baseUrl": "https://dashscope.aliyuncs.com",
    "model": "qwen-audio-3.1-asr-flash-filetrans",
    "models": ["qwen-audio-3.1-asr-flash-filetrans", "qwen-audio-3.0-asr-flash-filetrans"],
    "languageHints": "zh",
    "apiKeyConfigured": true,
    "apiKeyMasked": "****abcd",
    "apiKeySource": "db",
    "envHints": { "baseUrl": "DASHSCOPE_ASR_BASE_URL", "apiKey": "DASHSCOPE_API_KEY" }
  }
}
```

```
PUT /api/settings/asr
{
  "activeProvider": "dashscope",              // 可选；**省略=只保存配置、不改变当前生效 provider**
  "timeoutSeconds": 600,                       // 可选
  "local":    { "baseUrl": "...", "model": "whisper-1", "apiKey": "" },     // apiKey 空=保持
  "dashscope":{ "baseUrl": "...", "model": "...", "languageHints": "zh", "apiKey": "sk-..." }
}
200 → 同 GET 的视图
```

```
POST /api/settings/asr/test
{ "provider": "dashscope" }        // 可省略=测当前生效
200 { "ok": true, "provider": "dashscope", "model": "...", "latencyMs": 180,
      "message": "连通正常（上传凭证获取成功）" }
```

测试策略（低成本、无计费）：local 用 `GET {baseUrl}/`（whisper.cpp server 有响应即视为可达）；
dashscope 用 `GET {baseUrl}/api/v1/uploads?action=getPolicy&model={model}`（验证 Key 与模型可用，不产生识别费用）。

### 2.3 转写链路改造

- 新增包 `com.echolint.asr`：
  - `AsrProviderType`（`LOCAL`、`DASHSCOPE`；`fromValue` 宽松解析，非法值视为未设置）
  - `AsrProperties`（`@ConfigurationProperties("asr")`，承载 2.1 的 env 默认值）
  - `AsrSettingsService`：读写配置、拼视图、切换、测试；注入 `AppSettingRepository` + `SecretCipher` + `AsrProperties`。
  - `LocalWhisperProvider`：把现有 `TranscriptionService` 的调用逻辑迁入（multipart：`file`、`model`、
    `response_format=verbose_json`；解析 `text` / `language` / `duration` / `segments`）。
  - `DashScopeAsrProvider`：实现 §1 五步；结果映射：
    - `text` = `transcripts[0].text`
    - `segments` = `sentences[]` → `TranscriptSegment.of(null, null, begin/1000.0, end/1000.0, text)`
      （`sentences` 为空但 `text` 非空时退化为 `[0, duration]` 单段）
    - `duration` = `properties.original_duration_in_milliseconds / 1000`（四舍五入；缺失时用最后一句 end）
    - `language` = `language_hints` 第一个值（未配置时为空串）
- **`TranscriptionService` 保留类名、`record ChannelTranscript` 与 `transcribe(Path)` 签名**
  （`AudioTranscriptionService` 依赖它们，不要改动后者的编排逻辑：双声道仍是拆 L/R 后顺序调用两次）。
  内部改为：读当前 provider → 分发到对应实现；每次按当前配置构造 `WebClient`/`HttpClient`，
  不要再用固定 baseUrl 的单例 bean（`AppConfig.whisperWebClient` 可删或保留但不得再被转写使用；
  删除前确认没有其它引用）。
- 超时：本地 block 超时与云端「提交 + 轮询 + 下载」总预算都用 `asr.timeout-seconds`；
  云端轮询间隔 1.5s，超时抛 `BizException("百炼转写超时（{}s）：{}")`。
- 错误：HTTP 非 2xx / 任务 FAILED 时提取 DashScope 的 `code` + `message` 抛 `BizException`，消息会进「录音转写失败原因」。
- `application.yml`：
  - 新增 `asr:` 段（含 §2.1 的 env 占位符）；
  - `whisper.request-timeout-seconds` 的硬编码 600 改由 `asr.timeout-seconds` 接管（保留旧属性类不报错即可，
    不再作为转写超时来源）。
- 测试（不联网）：
  - DashScope 结果 JSON → segments/duration/text 映射（含 `sentences` 为空、带 `speaker_id` 的情况）；
  - `AsrSettingsService`：env 回退、掩码输出、PUT 空 apiKey 保持原值、未就绪时拒绝切换、默认 provider 选择。
  - 参考现有 `backend/src/test/java/com/echolint/**` 的写法（JUnit5 + Mockito，如有）。
- `.env.example` 增加 ASR 段说明（见 §2.1），并注明「百炼 Key 可留空，稍后在设置页录入（加密落库）」。

### 2.3.1 评审修复要点（2026-11-10 评审后追加，已实现）

- **结果体大小**：WebClient 必须显式 `maxInMemorySize`（建议 32MB）。长录音的结果 JSON 含
  `sentences + words`，20–30 分钟即可超过默认 256KB，否则整条转写失败（本地/百炼都要设）。
- **错误消息脱敏**：上游响应体可能包含临时 OSS 的 `policy/signature/upload_host/oss_access_key_id`，
  **不得**拼进 `BizException`（会进录音 `errorMessage` 并对 REVIEWER/VIEWER 可见）；只回 HTTP 状态 +
  `code/message/request_id` + 缺失字段名，完整响应仅 DEBUG 日志脱敏打印。
- **显式切换**：`asr.provider` 未落库时默认 `local`；PUT 未带 `activeProvider` 时保持当前 provider 不变
  （当前值显式落库，避免「就绪状态变化导致漂移」）。仅保存 Key 不会让转写悄悄上云。
- **多声道降级**：分轨失败或任一路 ASR 失败而走「单路降级」时，若源文件是多声道，先用 ffmpeg
  downmix 成 16k mono 再转写，并记录 WARN；否则云端只会转 channel 0，另一侧对话被静默丢弃（有漏审风险）。
- **测试**：HTTP 五步链路与本地 provider 用 JDK `com.sun.net.httpserver.HttpServer` 起假服务覆盖
  （不引新依赖），断言 multipart 字段、两个必需 header、`oss://`、预签名下载不带 Authorization、
  >256KB 结果体、错误消息不含凭证类字段。

### 2.4 兼容性红线

- 未配置百炼时，系统行为必须与今天完全一致（默认 local）。
- 不改动录音上传、流水线、双声道编排、结果落库字段。
- 不改动现有 `WhisperProperties` 的对外行为（可保留）。

## 3. 前端契约（frontend/）

### 3.1 目标信息架构

`/settings` 拆为子路由 + 二级导航（`SettingsLayout.vue` 承载页头、二级导航与 `<router-view />`）：

| 路由 | 页面 | 可见性 | 内容 |
|---|---|---|---|
| `/settings` | → 重定向 `/settings/screening` | — | 兼容旧链接 |
| `/settings/screening` | 复筛模型 | 所有登录用户 | 现有 `ActiveModelCard` + `ModelSwitchCard` + `ProviderRegistryCard`(ADMIN) + `AbEvalCard`(ADMIN)，重新编排 |
| `/settings/transcription` | 语音转写 | ADMIN | **新增**：provider 切换 + 本地/百炼配置 + 连通性测试 |
| `/settings/rag` | 知识增强 | ADMIN | 现有 `RagCard` |
| `/settings/notifications` | 通知与抽样 | ADMIN | 现有 `NotificationCard` |
| `/settings/runtime` | 运行环境 | 所有登录用户 | 现有 `ScopeCard` 内容 + ASR 环境变量说明 |

实现要点：

- 每个子路由 `meta: { titleKey: 'nav.settings.<xxx>', menuKey: 'SETTINGS', roles?: ['ADMIN'] }`；
  `roles` 由现有路由守卫拦截（提示 + 回首页）。
- 侧栏高亮：`AppSidebar` 的 `isActive` 改为前缀匹配（`route.path === path || route.path.startsWith(path + '/')`），
  保证 `/settings/*` 都高亮「系统设置」。**注意该文件当前有未提交改动，不要回退别人的修改。**
- 二级导航沿用现有视觉语言：可复用 `components/SegmentedControl.vue`，或参照 `Knowledge.vue` 的
  `view-tabs`（胶囊轨道 + 选中胶囊）；要求键盘可达（aria/tabindex 与 Knowledge 一致）。
- ADMIN 专用页面/导航项对非 ADMIN 隐藏（与现有 `canWrite` 逻辑一致）。
- 子路由组件用 `defineAsyncComponent` 按需加载（ADMIN 页面不下载给普通用户），保持现有做法。
- 旧 URL `/settings?xxx` 不再使用；`/settings` 重定向后不丢 query（可选）。

### 3.2 「语音转写」页面（新增）

页面结构（上→下）：

1. **当前生效**卡片：provider 名称 + 模型 + 状态点（已就绪/未配置）+ 密钥来源（环境变量/界面保存）+ 「测试连接」按钮。
2. **选择服务**：`SegmentedControl`（本地 whisper.cpp / 阿里云百炼）。未就绪的选项给 disabled 提示
   （例如「百炼未配置 API Key」）。选择后点「保存并生效」→ `PUT /api/settings/asr`。
3. **本地配置**卡：服务地址、模型名、API Key（password 输入，占位提示「留空表示不修改」）；附测试按钮。
4. **百炼配置**卡：API Key（留空不改）、模型（下拉候选 + 允许手填）、服务地址（默认 `https://dashscope.aliyuncs.com`，
   支持业务空间域名）、语言提示（默认 `zh`）、超时秒数。
5. **说明**区：本地需 whisper.cpp 服务在 `9900` 运行；百炼按音频时长/Token 计费、数据不出境；
   DashScope 临时上传接口的限流/有效期说明（见 §1）。
6. 只读兜底：非 ADMIN 不会进入本页；若后端 403 也要友好提示。

交互细节：

- 保存成功后用返回的视图局部刷新，不整页刷新；测试按钮独立 loading；失败展示后端 `message`。
- 密钥输入框留空的语义要在 UI 上明确（「留空表示不修改已保存的密钥」）。
- 所有文案走 i18n（zh/en 一一对应），键前缀 `settings.asr.*`、`settings.nav.*`。

### 3.3 页面整理要求（其余页面）

- 每页一个主标题 + 一句说明；不再出现 7 张卡片竖排。
- 复筛页内部再分区（当前生效 → 切换 → 供应商管理 → A/B 与评测），把「当前生效」与「切换」的重复信息合并，
  明确主次；供应商表格与 A/B 评测保持现有功能不变。
- RAG / 通知与抽样：功能不变，只做容器与标题整理（可保留原组件，必要时只改外层）。
- 运行环境页：`ScopeCard` 内容迁入，并补充 ASR 相关环境变量（`WHISPER_*`、`DASHSCOPE_ASR_*`）。

### 3.4 API 层与测试

- `frontend/src/api/index.js` 增加：
  - `getAsrSettings()` / `updateAsrSettings(payload)` / `testAsr(provider?)`
  - 配套 `frontend/src/utils/asr.js`（或就近）做响应归一化（参照 `normalizeRagSettings` 等），
    至少保证缺字段时返回稳定结构（`null`/空数组），供页面安全渲染。
- 新增单测 `frontend/tests/asr.test.mjs`（node:test）：归一化函数、留空密钥语义、候选模型解析。
- 如路由/菜单相关测试（`tests/menu.test.mjs`）受子路由影响，一并更新。
- 验收命令（必须全绿）：
  ```
  cd frontend
  npm test
  npm run lint
  npm run format:check
  npm run i18n:audit
  npm run build
  ```

## 4. 协作约束

- 写作用域：后端同学仅 `backend/**` + `.env.example`；前端同学仅 `frontend/**`；Lead 负责 `docs/**`、根文件与部署。
- **不要执行** `git commit` / `git push` / `git checkout` / `git restore`；工作区已有他人未提交改动，**不要回退**。
- 不要改服务器、不要动 `deploy/`、不要改 `docker-compose.yml`。
- 完成后用 `send_message` 汇报：改动文件清单、关键设计取舍、测试命令与结果、遗留问题。

## 5. 部署（Lead 负责）

- 服务器安装本地 whisper.cpp（`ggml-small-q5_1` 量化模型，systemd 服务，监听 `172.22.0.1:9900`，
  `MemoryMax=700M`、`OOMScoreAdjust=1000`，避免误杀 postgres/backend）。
- `docker-compose.yml`（服务器上的 `/opt/echolint/docker-compose.yml`）给 backend 补
  `extra_hosts: host.docker.internal:host-gateway`。
- 构建 jar / dist → 上传 → 重建 backend/frontend → 在设置页录入百炼 Key → 分别验证
  local / dashscope 切换与真实录音转写。

## 6. 第二轮：设置页去重合并 + 密钥只存数据库（2026-11-10）

### 6.1 目标

1. **合并重复卡片**：`/settings/screening` 里「当前生效」与「切换模型」重复（选择的模型就是生效模型），
   「AI 供应商」表格与切换卡里的供应商网格也重复 → 三合一成一张卡片。
   `/settings/transcription` 同样合并：「当前生效」+「选择服务」+「本地/百炼两张配置卡」→ 一张卡片 + 只显示选中 provider 的配置。
2. **API Key 只存数据库**：不再把环境变量当运行时密钥来源；环境变量仅作为**首次启动的一次性引导值**
   （写入加密库后即不再读取）。前端只显示掩码（如 `****LOOY`），任何接口不得返回明文/密文。
3. 顺带清理同类重复：运行环境页去掉「密钥用环境变量」的说明（改为：密钥在设置页录入、加密落库）；
   RAG / 通知页只合并明显重复的信息块。

### 6.2 后端契约变更（backend/）

**ASR 设置视图（GET/PUT /api/settings/asr）**：删除 `apiKeySource`、`envHints` 字段；其余不变：

```json
{
  "activeProvider": "dashscope",
  "timeoutSeconds": 600,
  "local":     { "label": "本地 whisper.cpp", "ready": true, "baseUrl": "...", "model": "whisper-1",
                 "audioPath": "/v1/audio/transcriptions", "apiKeyConfigured": true, "apiKeyMasked": "****ocal" },
  "dashscope": { "label": "阿里云百炼", "ready": true, "baseUrl": "https://dashscope.aliyuncs.com",
                 "model": "qwen-audio-3.1-asr-flash-filetrans",
                 "models": ["qwen-audio-3.1-asr-flash-filetrans", "qwen-audio-3.0-asr-flash-filetrans"],
                 "languageHints": "zh", "apiKeyConfigured": true, "apiKeyMasked": "****LOOY" }
}
```

- **密钥解析只读 DB**。启动时若 DB 中该 provider 的密钥缺失、而环境变量有值（`WHISPER_API_KEY` / `DASHSCOPE_API_KEY`），
  则加密写入 DB 并记录日志（一次性 bootstrap），此后不再读环境变量。
- 未配置密钥 → `ready=false`，切换时给「请在设置页录入 API Key」的提示（不再提示 env 变量名）。
- `dashscope.baseUrl` 允许粘贴文档里的完整接口地址（带 `/api/v1` 甚至具体路径）：后端保存与读取时统一归一化为
  `scheme://authority`，避免拼成 `/api/v1/api/v1/...` 返回 404（2026-11-10 线上实测问题）；业务空间域名只填到域名根。
- `base-url / model / language-hints / timeout` 仍允许 env 作为默认值（非密钥，不敏感）。
- AI 复筛供应商：密钥本来就在 DB（启动 seed 时加密落库），本次仅确认 `ProviderView.apiKeyEnv` 不再被前端展示；
  提供内置供应商密钥的录入入口使用既有 `PUT /api/settings/providers/{key}`。
- 测试：启动 bootstrap 只写一次、DB 优先于 env、视图不含 `apiKeySource/envHints`、PUT 空 key 保持原值。

### 6.3 前端信息架构（frontend/）

**`/settings/screening`：一张主卡片 + A/B 评测**

```text
┌ AI 复筛模型 ────────────────────────────────────────────── 作用于语义复筛与敏感词挖掘 ┐
│ 当前生效：DeepSeek · deepseek-flash  [配置默认]   ● Key 已配置           [测试连通性]  │
│ ───────────────────────────────────────────────────────────────────────────────────── │
│ (ADMIN) 选择供应商（点击即选中，卡片内含 编辑 图标；未配置 Key 的卡片灰显并提示）        │
│  [阿里云百炼]   [DeepSeek ✓]   [OpenAI]     [+ 新增供应商]                             │
│ 模型 [deepseek-flash ▾]                       [测试]  [保存并生效]                      │
│ 提示：保存后立即生效；已有历史结论不重算。                                                │
└────────────────────────────────────────────────────────────────────────────────────────┘
┌ A/B 与模型评测 ───────────────────────────────────────────────────────────────────────┐
│ 现有 AbEvalCard（保留，仅做外层标题/间距整理）                                          │
└────────────────────────────────────────────────────────────────────────────────────────┘
```

- 数据：供应商网格用 **registry**（`GET /api/settings/providers`：label/baseUrl/密钥掩码/builtIn/enabled/候选模型），
  当前选中/生效用 **screen-model view**；两者合并渲染，不再保留独立「AI 供应商」表格卡片。
- 供应商「编辑 / 新增 / 删除」沿用现有 `ProviderFormDialog`（删除仅自定义供应商；内置不可删）。
- 删除 `ActiveModelCard.vue`、`ProviderRegistryCard.vue`（或改造为内部子组件）；`ModelSwitchCard.vue` 改造成合并卡片。
- 非 ADMIN：只显示「当前生效」行 + 只读提示，不加载 registry、不显示网格。

**`/settings/transcription`：一张卡片**

```text
┌ 语音转写 ───────────────────────────────────────── 默认本地 whisper.cpp，可切换阿里云百炼 ┐
│ 当前生效：阿里云百炼 · qwen-audio-3.1-asr-flash-filetrans  ● Key 已配置 ****LOOY   [测试连接] │
│ 服务：[本地 whisper.cpp] [阿里云百炼 ✓]                                                      │
│ ── 选中 provider 的配置（只显示一张）────────────────────────────────────────────────────── │
│ 本地：服务地址 / 模型 / API Key（可选，掩码显示、留空不改）                                  │
│ 百炼：API Key（掩码、留空不改）/ 模型 / 服务地址 / 语言提示 / 超时                          │
│ 说明：数据不出境、按量计费、临时上传限流、密钥加密落库                                       │
│                                          [测试连接]  [保存并生效]                          │
└────────────────────────────────────────────────────────────────────────────────────────────┘
```

- 删除重复的「当前生效卡」「两张配置卡」「每卡各自测试按钮」；只保留顶部状态行 + 一张配置面板 + 一组操作。
- 密钥输入框一律 `type=password`、placeholder 显示当前掩码；提交时空=不改；不显示「来自环境变量」。
- 未就绪 provider 在选择器里灰显并提示「未配置 API Key，请先填写并保存」。

**`/settings/runtime`**：删除 `DEEPSEEK_API_KEY` / `DASHSCOPE_API_KEY` / `WHISPER_API_KEY` 等**密钥类**环境变量示例，
改为一句「所有 API Key 在系统设置页录入，AES-GCM 加密存数据库，界面只显示掩码」；保留 URL/模型/超时等非密钥变量示例。

**RAG / 通知页**：只合并明显重复块（例如状态行与正文重复的说明），功能与接口不动。

### 6.4 验收

- 后端 `mvn -B -q test` 全绿（新增 bootstrap/视图字段变更测试）。
- 前端 `npm test / lint / format:check / i18n:audit / build` 全绿；设置页仍为 ADMIN 按需加载。
- 部署后验证：`GET /api/settings/asr` 不含 `apiKeySource`/`envHints`；两家密钥均为 DB（重启不丢）；
  `/settings/screening`、`/settings/transcription` 正常 200；密钥任何接口只回掩码。
