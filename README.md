# EchoLint · 录音稽核平台

> **EchoLint** = Echo（录音） + Lint（自动化体检）：把每一通录音当作待检代码，先机械地查关键词，再让 AI 读语境，最后交给人拍板。

基于 **本机 whisper.cpp** 的录音合规稽核服务：录音上传后自动完成 **转写 → DFA 初筛 → AI 语义复筛 →（命中则）人工复检**，人工复检结论自动回馈语料库，并从中挖掘新敏感词，形成自增长闭环；支持 PostgreSQL + pgvector 知识库 RAG 与全局 AI 对话留痕。

## 总体流程

```
┌────────┐   ┌───────────────────┐   ┌──────────────┐   ┌───────────────────┐   ┌──────────────┐
│ 手动上传 │ → │ ① Whisper 英转文   │ → │ ② DFA 初筛    │ → │ ③ AI 语义复筛      │ → │ ④ 人工复检     │
│ (≤200MB)│   │  (whisper.cpp)    │   │  敏感词词典    │   │   (模型可切换)    │   │  confirm/reject│
└────────┘   └───────────────────┘   └──────┬───────┘   └─────────┬─────────┘   └──────┬───────┘
                                            │ 未命中               │ 不构成违规          │
                                            ▼                     ▼                    │
                                        自动通过 ──────────→ 自动通过                  │
                                            ▲                                          │
                                            └──────────── 初筛 ∪ 复筛均违规 ──────────┘
                                                                          │
                                             ⑤ 结论回馈语料库 (few-shot)  │
                                             └── 挖掘新词 → 词典(停用待审核) ←┘
```

- **DFA 初筛**：Aho-Corasick 自动机 + 字符跳变（`f u c k`、`f.u.c.k` 也能命中 `fuck`），大小写不敏感。
  > 跳变只允许字母之间插入分隔符；字母缺失的写法（如 `f**k` 少了 `u`/`c`）不命中，这类漏网由 AI 语义复筛与人工复检兜底。
- **双声道（双轨）录音**：自动探测声道数，立体声时分离左右声道并**分别转写**，按时间对齐成对话（详见下文）。
- **AI 复筛**：结合上下文判断命中词是否构成真实违规，`few-shot` 自动注入人工复核语料；双轨录音会带上**说话人标注**（坐席违规与客户辱骂性质不同）。
  **模型可在「系统设置」页运行时切换**（DeepSeek / 阿里云百炼 / 任意兼容网关），切换立即生效、重启后保持（详见下文）。
- **人工复检**：初筛 + 复筛均判违规 → 进入复检队列；确认违规 / 判定误报。复筛不可用时**降级转人工**，不漏审。
- **语料回馈**：复检结论写入语料库（复筛时作为参考案例）；确认违规的录音由 AI 挖掘新敏感词入库（默认停用，人工审核后启用）。

## 技术栈

| 层 | 技术 |
|---|---|
| 后端 | Spring Boot 3.3 / Java 17, Spring Data JPA, WebClient, PostgreSQL 17 + pgvector（语义检索） |
| AI | DeepSeek（语义复筛 / 词挖掘）、阿里云百炼（RAG embedding）、本机 [whisper.cpp](https://github.com/ggml-org/whisper.cpp)（语音转写）；全部走兼容接口，录音与文本不出境 |
| 前端 | Vue 3 + Vite + Element Plus + vue-router + axios |
| 部署 | docker-compose（PostgreSQL/pgvector + 后端 + Nginx 前端） |

系统分层、核心流水线与 RAG 数据流见 **[docs/architecture.md](docs/architecture.md)**。

## 目录结构

```
echolint/
├── backend/                      # Spring Boot 后端
│   ├── src/main/java/com/echolint/
│   │   ├── config/               # 配置：AI 供应商、异步流水线、CORS/静态资源
│   │   ├── controller/           # 录音/复检/词典/语料/统计 接口
│   │   ├── dfa/                  # DFA 匹配器（AC 自动机 + 词规范化）
│   │   ├── domain/               # 枚举（状态机/违规类型/严重度）
│   │   ├── entity/               # JPA 实体
│   │   ├── exception/            # 业务异常与全局异常处理
│   │   ├── repository/
│   │   └── service/              # 流水线/转写/复筛/复检/挖掘/语料/统计/模型切换
│   ├── src/main/resources/       # application.yml + 预置词库 data.sql
│   └── src/test/                 # DFA 单元测试
├── frontend/                     # Vue3 前端（工作台/录音管理/人工复检/敏感词库/语料库/系统设置）
│   └── src/styles/index.css      # 设计令牌 + Element Plus 主题层
├── tools/seed_demo_data.py       # 演示数据生成/清理（直连 PostgreSQL，预览 UI 用）
├── tools/whisper-server.sh       # 本机 whisper.cpp 转写服务（伪装成兼容转写端点）
├── docs/screenshots/             # 界面截图
├── docs/postgres-migration.md    # MySQL → PostgreSQL 迁移、校验与回滚手册
├── docs/architecture.md          # 系统架构图、核心流程与代码地图
├── docs/rag-integration.md       # RAG 检索增强：架构/配置/API/索引/降级/评测
├── docker-compose.yml            # 一键部署
└── .env.example                  # 环境变量样例（数据库 / AI / RAG）
```

## 管理端界面

统一的设计语言：浅色侧栏 + 浅色分层内容区（渐变表面、柔和投影）、6px 圆角体系、字重/字距收敛、状态用「圆点 + 文字」胶囊表达。

| 工作台 | 录音管理 |
|---|---|
| ![工作台](docs/screenshots/dashboard.png) | ![录音管理](docs/screenshots/recordings.png) |

| 人工复检 | 敏感词库 |
|---|---|
| ![人工复检](docs/screenshots/review.png) | ![敏感词库](docs/screenshots/dictionary.png) |

双声道（双轨）录音的双栏对话视图与分声道试听：

![双声道对话](docs/screenshots/stereo-dialogue.png)

- **工作台**：6 个 KPI 卡（可点击跳转对应筛选）+ 近 7 日趋势折线（悬停查看数值）+ 处置分布条 + 知识库闭环面板。
- **录音管理**：状态胶囊筛选（带各状态数量）、行内命中/AI 结论/复检人、详情抽屉含**音频播放器**、AI 结论置信度条、命中词高亮、处理轨迹时间线；有处理中记录时自动轮询刷新。
- **AI 调用留痕**：抽屉底部可展开每次 AI 调用（语义复筛 / 敏感词挖掘）的**完整提示词与模型原始回复** ——
  系统提示词、用户提示词（DFA 命中词 + few-shot 语料 + 待审文本）、原始 JSON 回复、端点、耗时、字符数；
  失败的调用同样留痕，且保留**本该发出去的请求体**。复检工作区也可展开查看。
  「AI 为什么这么判」对一个合规审计产品本身就是需要留痕的审计信息，只存解析后的结论回答不了这个问题。
- **AI 对话记录**：ADMIN 可在「AI 对话记录」页按阶段/供应商/模型/状态/关联录音/日期筛选所有 AI 调用，
  查看完整系统/用户提示词、模型原始回复、原始请求 JSON、token/成本/耗时；列表不加载全文，详情按需拉取。
- **手动推进处理**：`待处理`、`处理失败`，以及停留超过 2 分钟的进行中记录，行内会出现「立即/推进处理」「重试」「重新处理」按钮（详情抽屉同样有），用于处理上传后服务重启、外部 API 失败等卡住的情况；已归档或已复检的记录不提供该按钮，避免覆盖既有结论。
- **人工复检**：左侧复检队列（可筛选）+ 右侧工作区（**逐句对照转写**、AI 判断置信度条、结论表单）；打开时**默认选中 AI 建议的违规类型**，提交后自动进入下一条。
- **逐句对照 + 音画联动**：每句话带起始时间、DFA 命中词高亮与标记胶囊（`命中 · xxx` / `AI 判违规原句`）；**点击句子跳转到对应音频时间点**，播放时**自动高亮并滚动到当前句**（可用「跟随播放」开关关闭），也可切回「全文」视图。
- **双栏对话 + 分声道试听**：双轨录音默认以左右双栏对话展示（左=坐席、右=客户），播放器可切「混合 / 坐席 / 客户」单独试听某一路。
- **敏感词库**：KPI 概览、DFA 检测工具（命中实时高亮预览）、词条表含命中次数条形图，AI 挖掘词带「待审核」标记。
- **报表**：日期范围 + 坐席/团队/方向筛选，汇总卡与按坐席/团队/违规类型统计，导出录音/违规/复核 CSV；敏感词库支持 CSV 导入导出与批量启停。
- **合规抽样与告警**：可按比例抽检 DFA 未命中的录音（界面显示「抽样」标记）；复核确认违规、流水线失败、待复核积压可推送 Webhook（支持 HMAC 签名），系统设置页可发送测试通知。
- **AI 治理与成本**：系统设置页可维护 AI 供应商（新增/编辑/停用/删除、密钥轮换与掩码展示、单价录入、连通性测试）；「成本看板」按日期/供应商/模型查看调用量、token 与预估成本。密钥以 AES-GCM 加密落库。
- **异步任务**：上传/重处理写入 `pipeline_tasks` 持久队列，失败自动退避重试（30/120/300s，最多 3 次），耗尽进死信并只在死信时告警；服务重启恢复 RUNNING 任务；「任务队列」页（ADMIN）可查看/重试。
- **A/B 与评测**：可按比例把语义复筛流量分流到备用模型（只读配置，结论记录 `screenVariant`）；系统设置页可对标注语料抽样评测模型，返回 accuracy/precision/recall、混淆矩阵与逐条结果。
- **开放 API 与知识库**：可在「API 密钥」页创建外部接入密钥（明文仅显示一次），外部系统用 `X-API-Key` 上传录音并查询状态；「知识库」沉淀话术/SOP/处置规范，支持按分类、关键词检索。
- **界面与性能**：支持中/英切换（增量覆盖）；≤900px 响应式抽屉与键盘无障碍；「数据大屏」可全屏展示 KPI/趋势/违规分布/Top 坐席；前端按依赖拆包 + nginx gzip/长缓存；上传支持多文件顺序队列与进度。
- **语料库**：闭环流程说明条、违规/合规样本分类查看、导出 JSON。
- **系统设置**：查看当前生效的复筛模型与密钥状态，切换供应商 / 模型（立即生效、重启保持），并可对「当前模型」或「待保存的选择」做**连通性测试**（返回真实耗时与供应商报错原文）；页面同时说明哪些环节跟随切换、哪些不跟随。

### 双声道（双轨）录音

呼叫中心的双轨录音通常是 **左声道 = 坐席、右声道 = 客户**。系统会自动识别并分开处理：

```
上传 → ffprobe 探测声道数
        ├─ 1 声道：单路 Whisper 转写
        └─ 2 声道：ffmpeg 分离左右声道 → 两路分别 Whisper 转写 → 按 start 时间对齐合并
                     ↓
        转写文本（供 DFA/AI 复筛）+ 带 speaker/channel 的分段（供双栏对话视图）
```

| 能力 | 说明 |
|---|---|
| 双栏对话视图 | 左栏坐席、右栏客户，按时间顺序排列，气泡左右分明；保留时间戳、命中高亮、AI 原句标记、点击跳转、播放跟随 |
| 分声道试听 | 播放器旁可切「混合 / 坐席(左) / 客户(右)」，只听某一路 |
| AI 复筛增强 | 复筛与挖掘时文本按 `【坐席】…【客户】…` 标注，模型能区分"坐席辱骂客户"与"客户辱骂坐席" |
| 说话人可配置 | `app.stereo.left-speaker` / `right-speaker`（默认 坐席 / 客户） |
| 成本 | 双轨录音会调用 **两次** Whisper（每路一次），token 消耗约为单路的两倍 |
| 降级 | 未安装 ffmpeg、探测失败或分离失败 → 自动退回单路转写，不影响主流程 |

> Docker 镜像已内置 ffmpeg/ffprobe；**本地开发需自行安装**（macOS: `brew install ffmpeg`），否则双声道能力降级为单路。

### AI 复筛模型切换（DeepSeek / 阿里云百炼）

语义复筛与敏感词挖掘都是 **兼容 chat completions 协议**的调用，所以「换模型」= 换 `base-url + api-key + model` 三件套。
系统把这套组合做成**供应商清单**，并在「系统设置」页支持**运行时切换**：立即生效、无需重启、重启后保持。

| 能力 | 说明 |
|---|---|
| 切换入口 | 「系统设置」页 → 选供应商 → 选/填模型 → 保存并生效（影响之后的所有复筛与挖掘，已出结论的历史录音不重判） |
| 持久化 | 写入 `app_settings` 表（`screen.provider` / `screen.model`）；表为空时回退到配置项 `APP_SCREEN_PROVIDER` |
| 连通性测试 | 可测「当前生效」或「待保存」的选择（先验证再切换），返回真实耗时与供应商报错原文 |
| 候选模型 | 下拉候选来自配置，同时支持**直接输入**列表外的新模型名 —— 供应商上新模型无需改配置重启 |
| 判定留痕 | AI 结论里记录 `screenProvider / screenModel`，录音详情与复检工作区显示「判定模型」，便于回溯与横向对比 |
| 不参与切换 | **Whisper 转写固定走本机 whisper.cpp**；DFA 初筛是本地算法，不调用外部模型 |
| 降级保护 | 当前模型缺密钥 / 网络失败 / 额度不足时，DFA 命中的录音**降级转人工复检**（`aiResultJson.degraded=true`），既不自动放行也不卡死 |

内置供应商（`application.yml` → `app.screen-models.providers`）：

| 供应商 | 默认模型 | 端点 | 候选模型 |
|---|---|---|---|
| 阿里云百炼 | `qwen-plus` | `/chat/completions` | `qwen-plus` / `qwen-max` / `qwen-turbo` |
| DeepSeek | `deepseek-flash` | `/chat/completions` | `deepseek-flash` / `deepseek-v4-pro` |

启用 DeepSeek：

```bash
# 1. 在 .env 里补密钥（docker compose 自动读取）
DEEPSEEK_API_KEY=sk-xxxxxxxxxxxxxxxx

# 2. 重启后到「系统设置」页切到 DeepSeek；或直接指定首次启动的默认供应商
# APP_SCREEN_PROVIDER=deepseek
```

接入第三方供应商（自建网关、其它厂商）只需在 `application.yml` 追加一段：

```yaml
app:
  screen-models:
    providers:
      - id: my-gateway
        label: 自建网关
        base-url: https://llm.internal
        api-key: ${MY_GATEWAY_KEY:}
        api-key-env: MY_GATEWAY_KEY
        chat-path: /v1/chat/completions
        default-model: qwen-max
        models: [qwen-max, qwen-plus]
        json-mode: true          # 端点不支持 response_format 时设为 false
        max-tokens: 2048         # 可选：防止 JSON 输出被中途截断
        extra-body: {}           # 可选：透传字段，如 DeepSeek 的 thinking
```

### 本地转写（whisper.cpp，可选）

转写固定使用**本机 whisper.cpp**：录音不出本机、无时长费用、无并发限制，也不受 25MB 限制。

```bash
brew install whisper.cpp ffmpeg
tools/whisper-server.sh --daemon      # 默认加载 ~/models/whisper/ggml-large-v3-turbo.bin
```

然后在 `.env` 里加两行并 `docker compose up -d`：

```bash
WHISPER_BASE_URL=http://host.docker.internal:9900
WHISPER_API_KEY=local      # whisper.cpp 本身不校验密钥，但后端要求非空
```

**实测**（M1 Pro / 16 核 GPU / `large-v3-turbo` / Metal）：**约 12x 实时** —— 79 秒录音转写 6 秒；本项目双声道会调用两次，10 分钟双轨通话约需 1.5 分钟。

> **零代码改动是怎么做到的**：whisper.cpp 的 server 只暴露 `/inference`（1.9.5 实测无 `/v1/audio/transcriptions`），脚本用 `--inference-path /v1/audio/transcriptions` 把它**改名**成兼容转写路径。响应结构走兼容格式：`segments[].{start,end,text}` 同名同单位（秒），顶层也有 `language` / `duration` / `text`，因此 `TranscriptionService` 一行都不用改。
>
> 端口只绑 `127.0.0.1`；Docker Desktop 的 `host.docker.internal` 可以直达宿主回环，无需暴露到局域网。

> ⚠️ **转写配置用独立的 `whisper.*` 前缀**，与 `WHISPER_*` 环境变量一一对应，避免与其它供应商变量互相遮蔽。

### 逐句转写的数据来源与降级策略

| 场景 | 表现 |
|---|---|
| Whisper 返回 `verbose_json` 分段（`segments[].start/end/text`） | 每句带时间轴，音画双向联动完整可用 |
| 双声道录音（分段带 `speaker`/`channel`） | 额外提供「双栏对话」视图 + 分声道试听 |
| 无分段数据（历史数据 / 非标准转写） | 按句末标点切句展示文本与标记，时间戳显示 `--:--`，点击不跳转并在顶部提示 |
| AI 原句与分段边界不一致 | 先按全文偏移定位，失败则回退为「句子包含原句 / 原句包含句子」的匹配（原句带 `【说话人】` 前缀会先剥离） |

句子与命中的归属逻辑（含偏移定位、区间合并、播放定位）抽成了纯函数 `frontend/src/utils/segments.js`，并有单元测试：

```bash
cd frontend && npm test     # node --test，10 个用例
```

## 演示数据（可选）

想在空环境下预览界面：

```bash
python3 tools/seed_demo_data.py          # 写入 12 条演示录音（id >= 1000，含分段/命中/AI 结论）
python3 tools/seed_demo_data.py --with-audio   # 额外生成 #1001 的音轨：
                                               #   混合(静音) / 左声道 440Hz / 右声道 660Hz
                                               # 并把 #1001 造成双声道样本，可直接体验双栏对话与分声道试听
python3 tools/seed_demo_data.py --clean  # 清理演示数据
```

脚本会调用运行中的后端 `/api/dictionary/test` 计算命中位置，因此需要后端已启动；
默认后端地址 `http://localhost:8082`、PostgreSQL 容器 `echolint-postgres`（compose 服务 `postgres`），
可用 `--api` / `--container` 覆盖。脚本通过 `docker exec -i <容器> psql -U audit -d echolint` 写库，
显式写入的 demo id 会在末尾用 `setval` 重置序列，不会与后续真实数据的主键冲突。

## 快速开始（Docker 一键）

```bash
# 1. 配置本地转写与百炼 embedding
cp .env.example .env
tools/whisper-server.sh --daemon          # 本机 whisper.cpp（需先下载模型）
#   编辑 .env，填入 WHISPER_*、DASHSCOPE_API_KEY、DEEPSEEK_API_KEY

# 2. 构建并启动（首次构建需数分钟）
docker compose up -d --build

# 3. 访问
#    前端:   http://localhost:8081   （后端 API 经 Nginx 同源代理）
#    后端:   http://localhost:8082   （健康检查 /actuator/health）

# 端口说明（宿主机）：前端 8081 → Nginx:80；后端 8082 → 8080；
# PostgreSQL 5433 → 5432（只绑 127.0.0.1；服务名 postgres，库 echolint，用户 audit）。
# 若与本机已有服务冲突，改 docker-compose.yml 中的宿主机端口映射即可。
```

停止：`docker compose down`（数据保存在 named volume，`docker compose down -v` 可清空）。

### 登录与权限

系统使用 **会话 Cookie**（`HttpOnly` + `SameSite=Lax`）认证，前端不保存 token；`/api/**` 与 `/uploads/**`
均需登录，未登录返回 JSON 401。首次启动且用户表为空时会自动创建管理员：

```bash
# 管理员账号来自环境变量；默认 admin / admin12345（生产必须覆盖）
APP_ADMIN_USERNAME=admin
APP_ADMIN_PASSWORD=<至少 8 位的强密码>
```

角色：`ADMIN`（全部权限）、`REVIEWER`（上传、复检、认领、重开）、`VIEWER`（只读）。
管理员可在「用户管理」页创建/停用账号、重置密码；所有敏感操作写入「操作审计」。

复核工作流默认单人复核；设置 `APP_REVIEW_REQUIRED_APPROVALS=2` 后进入**双人复核**：每次提交落一条复核记录，
达到人数才落最终结论并回馈语料库，同一人不能连续复核同一条录音。终态录音可由管理员「重开复核」。

### 可观测性与接口文档

```bash
# 健康检查
curl http://localhost:8082/actuator/health
# Prometheus 指标（后端端口只绑回环/内网，切勿直接暴露公网）
curl http://localhost:8082/actuator/prometheus
# OpenAPI JSON / Swagger UI
#   http://localhost:8082/v3/api-docs
#   http://localhost:8082/swagger-ui/index.html
```

- 数据库为 **PostgreSQL 17 + pgvector**，结构由 **Flyway** 管理：`V1` 为基线标记（既有库自动 baseline 到 v1）；
  `V2__postgres_pgvector_rag.sql` 负责 `CREATE EXTENSION vector`、`knowledge_chunks` 表与 HNSW 索引，
  `V3__rag_embedding_1024.sql` 将 HNSW 调整为阿里云百炼 embedding 的 **1024 维**；
  其余业务表仍由 Hibernate `ddl-auto` 管理；预置词典/语料由 `DataSeedRunner` 在 JPA 建表后幂等执行。
  从 MySQL 老库迁移的完整步骤（pgloader 只搬数据、行数/序列/pgvector 校验、回滚）见
  **[docs/postgres-migration.md](docs/postgres-migration.md)**。
- 上传做 **SHA-256 去重**（重复文件 409，返回已存在录音 id）与 **magic bytes 校验**（内容与扩展名不符 400）；
  文件先写临时文件再原子移动，失败/重复自动清理。
- 容器日志已配置轮转（`max-size=10m, max-file=3`），支持优雅停机（`server.shutdown=graceful`）。

## 开放 API（外部系统接入）

1. ADMIN 在「API 密钥」页创建密钥（明文只显示一次，请妥善保存）。
2. 外部系统带 `X-API-Key` 调用：

```bash
curl -X POST http://<host>/api/open/recordings/upload \
  -H "X-API-Key: elk_xxx" \
  -F "file=@call.mp3" \
  -F "agentName=张三" -F "teamName=A组" -F "direction=INBOUND"

curl http://<host>/api/open/recordings/1 -H "X-API-Key: elk_xxx"
```

- 上传复用内部校验/去重/持久任务队列；查询只返回状态、命中数、违规类型、复核结论、错误信息等限定字段。
- 密钥停用后立即失效；当前开放 API 未提供限流与租户隔离，请只在可信网络内使用。

## RAG 检索增强（Batch 6）

知识库/SOP 与人工复核语料在写入时通过阿里云百炼 embedding 异步向量化（PostgreSQL + pgvector，
HNSW 余弦索引），复筛时按语义召回并注入提示词：

- **知识库 RAG**：按分块召回 SOP/话术/处置规范，带上「知识库参考」；`aiResultJson.rag` 与 AI 调用留痕记录引用了哪些知识。
- **语义 few-shot**：从人工复核语料按相似度取样（违规/合规各半），替代固定「最新 N 条」。
- **文档导入**：PDF / DOCX / TXT / MD 一键导入知识库，后台解析 + 分块 + 向量化，失败可重建。
- **管理端**：系统设置页「RAG 检索增强」卡片支持开关/检索参数/embedding 连通性测试/重建索引/with-RAG 对比评测；
  知识库页显示索引状态与文档导入入口。

默认关闭（`APP_RAG_ENABLED=false`，关闭时与旧版完全一致）。开启前在「AI 供应商」配置阿里云百炼
（默认 `provider=aliyun`、`model=qwen3.7-text-embedding`、`1024` 维；DeepSeek 不提供 embedding），
完整配置项、API、索引生命周期、降级行为、评测方法与已知限制见
**[docs/rag-integration.md](docs/rag-integration.md)**（默认值也见 `.env.example` 的 `APP_RAG_*` 段）。

## 集成适配器（多租户 / SSO / 流式 ASR / 说话人分离）

四类需要外部选型/部署的能力做成了**可插拔适配器 + 配置 + 状态接口**，**默认全部关闭、零行为变化**。
完整配置项、接口契约、OIDC/LDAP/流式 ASR/diarization 接入步骤、数据隔离分阶段方案与回滚说明见
**[docs/integrations.md](docs/integrations.md)**。

```bash
# 管理端状态（ADMIN）：是否启用/已配置、供应商、SSO 登录地址；不含任何密钥
curl http://localhost:8082/api/settings/integrations -b cookies.txt

# 登录页 SSO 公开配置（匿名可读；loginUrl 为空表示客户端适配器尚未实现）
curl http://localhost:8082/api/auth/sso/config
```

- **多租户**：默认单租户（`default`）。设 `APP_TENANT_ENABLED=true` + `APP_TENANT_MODE=HEADER` 后，
  `/api/**` 必须携带 `APP_TENANT_HEADER`（默认 `X-Tenant-Id`）指定的租户头，缺失/非法返回 JSON 400；
  本轮只提供租户上下文，**不做数据隔离**（迁移方案见文档）。
- **SSO/LDAP**：默认关闭，登录仍走本地账号；本轮只暴露适配器契约与只读状态，
  真实 OIDC/LDAP 对接需引入对应客户端依赖并实现 `SsoProvider`（步骤见文档）。
- **流式 ASR**：默认关闭，**不改变现有批量转写流程**；接入点（WebSocket/SSE、会话生命周期、背压）见文档。
- **说话人分离**：默认关闭，仅单声道需要；双声道仍走左右声道方案，降级策略见文档。
- 密钥（`client-secret`、`api-key`）只从环境变量/配置注入，接口与日志均不返回；关闭开关即可回滚。

## 本地开发

```bash
# 0. 准备 PostgreSQL 17 + pgvector（compose 把容器 5432 映射到宿主 5433，避免与本机 PG 冲突）
docker compose up -d postgres

# 1. 后端（默认连 localhost:5432；连 compose 容器时用宿主 5433）
cd backend
export DASHSCOPE_API_KEY=sk-...
export WHISPER_BASE_URL=http://127.0.0.1:9900
export WHISPER_API_KEY=local
# 本机 5432 没有 PostgreSQL 时，显式指向 compose 容器：
export SPRING_DATASOURCE_URL='jdbc:postgresql://localhost:5433/echolint'
export SPRING_DATASOURCE_USERNAME=audit
export SPRING_DATASOURCE_PASSWORD=audit123
mvn spring-boot:run

# 2. 前端（开发服务器，/api 与 /uploads 默认代理到 8080；
#    若后端跑在别的端口，用 VITE_API_TARGET 覆盖，例如 compose 的 8082）
cd frontend
npm install
VITE_API_TARGET=http://localhost:8082 npm run dev
```

> 连接数据库排查：`docker exec -it echolint-postgres psql -U audit -d echolint`；
> 宿主机也可用 `psql "postgresql://audit:audit123@127.0.0.1:5433/echolint"`。
> 老库迁移见 [docs/postgres-migration.md](docs/postgres-migration.md)。

### 环境变量（均有默认值）

| 变量 | 默认 | 说明 |
|---|---|---|
| `WHISPER_BASE_URL` | `http://127.0.0.1:9900` | **转写**端点（本机 whisper.cpp；容器内用 `http://host.docker.internal:9900`） |
| `WHISPER_API_KEY` | `local` | **转写**密钥；whisper.cpp 不校验，但后端要求非空 |
| `WHISPER_MODEL` | `whisper-1` | 转写模型名（whisper.cpp 会忽略该值，但请求仍需带上） |
| `DASHSCOPE_API_KEY` | (空) | 阿里云百炼 API Key，用于 RAG embedding |
| `DASHSCOPE_BASE_URL` | `https://dashscope.aliyuncs.com/compatible-mode/v1` | 百炼兼容端点（业务空间不同时按控制台替换） |
| `DASHSCOPE_CHAT_MODEL` | `qwen-plus` | 百炼作为复筛模型时的默认模型 |
| `DEEPSEEK_API_KEY` | (空) | DeepSeek 密钥，用于复筛 / 词挖掘（切换前必须配置） |
| `DEEPSEEK_BASE_URL` | `https://api.deepseek.com` | DeepSeek 端点（可指向代理）；compose 已透传 |
| `DEEPSEEK_SCREEN_MODEL` | `deepseek-flash` | DeepSeek 默认模型 |
| `APP_SCREEN_PROVIDER` | `deepseek` | 首次启动（`app_settings` 表为空）时的复筛供应商 |
| `APP_ADMIN_USERNAME` | `admin` | 首次启动引导管理员用户名（用户表为空时创建） |
| `APP_ADMIN_PASSWORD` | `admin12345` | 引导管理员密码，**生产必须覆盖** |
| `APP_REVIEW_REQUIRED_APPROVALS` | `1` | 一条录音需要几位复核人通过才落最终结论（2 = 双人复核） |
| `APP_SAMPLE_DFA_MISS_RATE` | `0` | DFA 未命中时的抽样比例（0~1）；抽样录音也会走 AI 复筛 |
| `APP_NOTIFY_ENABLED` | `false` | 是否启用 Webhook 通知 |
| `APP_NOTIFY_WEBHOOK_URL` | (空) | Webhook 地址（仅配置，不在接口暴露） |
| `APP_NOTIFY_WEBHOOK_SECRET` | (空) | HMAC-SHA256 签名密钥，请求头 `X-EchoLint-Signature` |
| `APP_NOTIFY_TIMEOUT_SECONDS` | `5` | Webhook 超时 |
| `APP_NOTIFY_BACKLOG_THRESHOLD` | `50` | 每日待复核积压告警阈值 |
| `APP_SCREEN_CHUNK_CHARS` | `12000` | AI 复筛单块字符数，超长转写自动分块 |
| `APP_SCREEN_MAX_CHUNKS` | `6` | 单条录音最多复核块数（超出截断并告警） |
| `APP_SCREEN_MIN_CONFIDENCE` | `0.6` | 判违规但置信度低于此值时降级人工复核 |
| `APP_SECRET_KEY` | (dev fallback) | AI 供应商密钥加密主密钥，**生产必须配置**；变更后旧密文需重新录入 |
| `APP_SCREEN_DAILY_TOKEN_LIMIT` | `0` | 每日 token 配额（0=不限；成功调用的 input+output） |
| `APP_SCREEN_REQUESTS_PER_MINUTE` | `0` | 每分钟 AI 请求数上限（0=不限，进程内按供应商计数） |
| `APP_SCREEN_AB_ENABLED` | `false` | 语义复筛 A/B 分流开关（只读配置，仅语义复筛参与） |
| `APP_SCREEN_AB_PERCENT` | `0` | B 组流量百分比（0~100） |
| `APP_SCREEN_AB_PROVIDER` | (空) | B 组供应商标识（缺失/停用/缺密钥自动回退 A） |
| `APP_SCREEN_AB_MODEL` | (空) | B 组模型名（可空=供应商默认模型） |
| `SPRING_DATASOURCE_URL/USERNAME/PASSWORD` | `jdbc:postgresql://localhost:5432/echolint`, audit/audit123 | 数据库连接（compose 内为 `jdbc:postgresql://postgres:5432/echolint`；本机连容器用宿主 5433） |
| `APP_UPLOAD_DIR` | `data/uploads` | 录音文件存储目录 |
| `APP_STEREO_ENABLED` | `true` | 是否启用双声道分轨处理 |
| `APP_STEREO_LEFT_SPEAKER` | `坐席` | 左声道说话人名称 |
| `APP_STEREO_RIGHT_SPEAKER` | `客户` | 右声道说话人名称 |
| `APP_TENANT_ENABLED` | `false` | 多租户解析开关（关闭=单租户 `default`） |
| `APP_TENANT_MODE` | `SINGLE` | `SINGLE` / `HEADER`（HEADER 模式校验租户头） |
| `APP_TENANT_HEADER` | `X-Tenant-Id` | HEADER 模式读取的租户请求头 |
| `APP_SSO_ENABLED` | `false` | SSO/LDAP 开关（关闭时仍用本地账号登录） |
| `APP_SSO_PROVIDER` | `OIDC` | `OIDC` / `LDAP` |
| `APP_SSO_ISSUER` | (空) | OIDC issuer；LDAP 为 `ldap(s)://host:port` |
| `APP_SSO_CLIENT_ID` | (空) | OIDC client_id；LDAP 为绑定 DN |
| `APP_SSO_CLIENT_SECRET` | (空) | OIDC client_secret；LDAP 为绑定密码（不通过接口返回） |
| `APP_SSO_ROLE_MAPPING_JSON` | (空) | IdP 组 → 本地角色映射 JSON（`{"group":"ADMIN"}`） |
| `APP_ASR_STREAMING_ENABLED` | `false` | 实时（流式）转写开关，不影响批量转写 |
| `APP_ASR_STREAMING_PROVIDER` | (空) | 流式转写供应商标识 |
| `APP_ASR_STREAMING_ENDPOINT` | (空) | 流式转写网关地址（`wss(s)://`/`https://`） |
| `APP_ASR_STREAMING_API_KEY` | (空) | 流式转写密钥（不通过接口返回） |
| `APP_DIARIZATION_ENABLED` | `false` | 单声道说话人分离开关（双声道不受影响） |
| `APP_DIARIZATION_PROVIDER` | (空) | 说话人分离供应商标识 |
| `APP_DIARIZATION_ENDPOINT` | (空) | 说话人分离服务地址（`http(s)://`） |
| `APP_DIARIZATION_API_KEY` | (空) | 说话人分离密钥（不通过接口返回） |

### RAG 环境变量（Batch 6）

数据库迁移到 PostgreSQL + pgvector 后，可按需开启知识库语义检索；**默认全部关闭/有默认值**，
关闭时复筛行为与旧版完全一致。完整说明见 `.env.example` 与 [docs/rag-integration.md](docs/rag-integration.md)。

| 变量 | 默认 | 说明 |
|---|---|---|
| `APP_RAG_ENABLED` | `false` | RAG 总开关；关闭时不发起任何 embedding 调用 |
| `APP_RAG_TOP_K` | `4` | 知识库召回条数（1~20） |
| `APP_RAG_MIN_SCORE` | `0.25` | 余弦相似度下限（0~1） |
| `APP_RAG_MAX_CONTEXT_CHARS` | `2400` | 注入 prompt 的知识片段总字符上限 |
| `APP_RAG_CORPUS_EXAMPLES` | `5` | 语义 few-shot 条数（0 = 回退最新 N 条） |
| `APP_RAG_EMBEDDING_PROVIDER` | `aliyun` | embedding 供应商（复用 `ai_providers` 的 base-url/api-key） |
| `APP_RAG_EMBEDDING_MODEL` | `qwen3.7-text-embedding` | 阿里云百炼 embedding 模型 |
| `APP_RAG_EMBEDDING_PATH` | `/embeddings` | embedding 端点路径（base-url 已含 `/compatible-mode/v1`） |
| `APP_RAG_EMBEDDING_DIMENSIONS` | `1024` | 向量维度，必须与 V3 迁移的 HNSW 索引一致 |
| `APP_RAG_EMBEDDING_SEND_DIMENSIONS` | `true` | 是否在请求体显式发送 `dimensions` |
| `APP_RAG_EMBEDDING_TIMEOUT` | `30` | embedding 请求超时（秒） |
| `APP_RAG_EMBEDDING_BATCH_SIZE` | `8` | 单批 embedding 条数（阿里云 v3/v4 最多 10 条/批） |
| `APP_RAG_CHUNK_CHARS` | `600` | 知识库分块字符数 |
| `APP_RAG_CHUNK_OVERLAP` | `80` | 相邻分块重叠字符数 |
| `APP_RAG_CORPUS_MAX_CHARS` | `6000` | 语料 embedding 前的截断长度 |
| `APP_RAG_INDEX_POOL` | `2` | 异步索引线程数 |
| `APP_RAG_STARTUP_BACKFILL` | `true` | 启动时回填未索引条目/语料 |
| `APP_RAG_DOCUMENTS_DIR` | `data/knowledge-docs` | 文档导入目录 |
| `APP_RAG_DOCUMENTS_MAX_BYTES` | `20971520` | 单文档大小上限（20MB） |
| `APP_RAG_DOCUMENTS_EXTENSIONS` | `pdf,docx,txt,md` | 允许导入的扩展名 |

## API 一览

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/recordings/upload` | 上传录音（multipart `file`；可选元数据 `agentName`/`teamName`/`callTime`/`direction`/`businessLine`），异步启动稽核流水线 |
| GET | `/api/recordings?statuses=&keyword=&assignedTo=&priority=&agentName=&teamName=&direction=&from=&to=&page=&size=` | 录音分页查询（时间优先 callTime，缺失回退 uploadTime；`size` ≤100） |
| GET | `/api/recordings/{id}` | 详情（转写/命中/复筛结果/复检信息） |
| GET | `/api/recordings/{id}/logs` | 处理轨迹日志 |
| GET | `/api/recordings/{id}/ai-invocations` | AI 调用留痕（完整请求体 + 模型原始回复） |
| POST | `/api/recordings/{id}/retry` | 失败重试 |
| DELETE | `/api/recordings/{id}` | 删除（含文件与日志） |
| POST | `/api/reviews/{id}` | 人工复检 `{result: CONFIRMED_VIOLATION\|FALSE_POSITIVE, violationType?, comment?}` |
| GET/POST/PUT/DELETE | `/api/dictionary...` | 敏感词 CRUD（变更后自动重建 DFA 匹配器） |
| POST | `/api/dictionary/test` | 检测工具：`{text}` → 命中列表 |
| GET/POST/DELETE | `/api/corpus...` | 语料库查询/录入/删除 |
| GET | `/api/corpus/export` | 语料导出 JSON |
| GET | `/api/stats/overview` | 工作台统计 |
| GET | `/api/settings/screen-model` | 当前复筛模型 + 全部候选供应商 |
| PUT | `/api/settings/screen-model` | 切换复筛模型 `{providerId, model?}`（落库持久化） |
| POST | `/api/settings/screen-model/test` | 连通性测试（请求体留空则测当前生效模型） |
| POST | `/api/settings/screen-model/evaluate` | 基于标注语料评测模型 `{providerId, model?, sampleSize?}`（ADMIN，≤20 条） |
| GET | `/api/settings/notifications` | 抽样与 Webhook 通知配置（ADMIN，不返回密钥） |
| POST | `/api/settings/notifications/test` | 发送测试 Webhook 并返回结果（ADMIN） |
| GET | `/api/settings/integrations` | 集成适配器状态：多租户/SSO/流式ASR/说话人分离（ADMIN，不返回密钥） |
| GET/POST | `/api/settings/providers` | AI 供应商列表/新增（ADMIN，密钥只回掩码） |
| PUT/DELETE | `/api/settings/providers/{key}` | 修改（全量，apiKey 可选）/ 删除自定义供应商（ADMIN） |
| POST | `/api/settings/providers/{key}/test` | 供应商连通性测试（ADMIN） |
| GET | `/api/stats/ai-usage?from=&to=` | AI 调用量/成本统计（ADMIN，按日/供应商/模型） |
| GET | `/api/tasks?status=&recordingId=&page=&size=` | 异步稽核任务分页（ADMIN，size≤100） |
| POST | `/api/tasks/{id}/retry` | 重试死信/失败任务（ADMIN，审计 `TASK_RETRY`） |
| GET/POST | `/api/api-keys` | 开放 API 密钥列表 / 创建（ADMIN；明文仅创建响应返回一次） |
| DELETE | `/api/api-keys/{id}` | 停用密钥（ADMIN，审计 `API_KEY_REVOKE`） |
| POST | `/api/open/recordings/upload` | 外部系统上传（`X-API-Key`；multipart file + 可选元数据） |
| GET | `/api/open/recordings/{id}` | 外部系统查询处理状态（`X-API-Key`） |
| GET/POST/PUT/DELETE | `/api/knowledge...` | 知识库/SOP 查询与维护（读登录可读，写 ADMIN/REVIEWER） |
| POST | `/api/knowledge/documents` | 导入 PDF/DOCX/TXT/MD 文档（ADMIN/REVIEWER，异步解析 + 向量化） |
| POST | `/api/knowledge/{id}/reindex` | 重建单条知识库索引（ADMIN/REVIEWER） |
| POST | `/api/knowledge/search` | 语义检索预览 `{query, topK}`（ADMIN/REVIEWER） |
| GET/PUT | `/api/settings/rag` | RAG 设置查看/更新（ADMIN） |
| POST | `/api/settings/rag/test` | embedding 连通性测试（ADMIN） |
| POST | `/api/settings/rag/reindex` | 重建 RAG 索引 `{scope: knowledge\|corpus\|all}`（ADMIN） |
| POST | `/api/settings/rag/evaluate` | with/without RAG 对比评测（ADMIN，≤20 条） |
| GET | `/api/ai-invocations?stage=&providerId=&model=&success=&recordingId=&from=&to=&page=&size=` | AI 对话记录分页（ADMIN；列表不返回全文） |
| GET | `/api/ai-invocations/facets` | AI 对话记录筛选候选（ADMIN） |
| GET | `/api/ai-invocations/{id}` | AI 调用完整请求/回复（ADMIN） |

报表与运营：

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/reports/recordings.csv` | 录音清单 CSV（与列表同过滤参数，UTF-8 BOM，≤10000 行） |
| GET | `/api/reports/violations.csv` | 违规明细 CSV（仅 VIOLATION_CONFIRMED） |
| GET | `/api/reports/reviews.csv` | 复核记录 CSV（按复核时间 `from`/`to`） |
| GET | `/api/stats/report?from=&to=` | 按状态/违规类型/坐席 Top/团队 Top/日期统计 |
| GET | `/api/dictionary/export` | 敏感词导出 CSV（ADMIN） |
| POST | `/api/dictionary/import` | 敏感词导入 CSV（multipart `file`，≤5000 行 / 5MB，ADMIN） |
| PATCH | `/api/dictionary/bulk-enabled` | 批量启用/停用 `{ids, enabled}`（ADMIN） |

认证与权限：

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/auth/login` | `{username,password}` → 用户信息；失败 401 |
| GET | `/api/auth/sso/config` | 登录页 SSO 公开配置 `{enabled,provider,configured,loginUrl}`（匿名可读，不含密钥） |
| POST | `/api/auth/logout` | 注销会话 |
| GET | `/api/auth/me` | 当前登录用户 |
| POST | `/api/auth/password` | 修改密码 `{oldPassword,newPassword}` |
| GET/POST | `/api/users` | 用户列表 / 新建（ADMIN） |
| PUT/DELETE | `/api/users/{id}` | 修改角色/启停/重置密码、禁用（ADMIN） |
| GET | `/api/audit-logs?page&size&actor&action` | 操作审计（ADMIN） |

复核工作流：

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/recordings?statuses=&keyword=&assignedTo=&priority=&page=&size=` | 支持指派/优先级筛选，`size` 上限 100 |
| POST | `/api/recordings/{id}/claim` | 认领复核（ADMIN/REVIEWER） |
| PUT | `/api/recordings/{id}/assign` | 指派复核人 `{username}`（ADMIN） |
| POST | `/api/recordings/{id}/reopen` | 重开终态录音（ADMIN） |
| GET | `/api/recordings/{id}/reviews` | 复核历史 |
| POST | `/api/reviews/{id}` | 提交复核；复核人取自登录态，支持双人复核 |

## 录音状态机

```
PENDING → TRANSCRIBING → DFA_CHECKING → AI_CHECKING → NEEDS_REVIEW → VIOLATION_CONFIRMED
                                                      │            └───────→ FALSE_POSITIVE
                                                      └──→ COMPLIANT（任一步判定合规）
任意步骤异常 → FAILED（可在录音管理重试）
```

## 词典使用说明

- 词条保存时自动**规范化**：小写、去空格/标点（`Kill You` → `killyou`），匹配时原文中的空格/标点不影响命中（`f u c k`、`f.u.c.k` 均可命中 `fuck`）。
- 匹配为**子串语义**（`unfuckingbelievable` 会命中 `fuck`），且不做字母替换（`f**k` 不命中由复筛兜底），误报由 AI 复筛兜底——这是两段式设计的初衷。
- **AI 挖掘的新词默认停用**，需在敏感词库页人工审核后启用（列表按 `来源=AI 挖掘` 过滤）。
- 预置词典**中英双语**（185 词，中文 93 条），覆盖辱骂/歧视/威胁/骚扰/色情/诈骗/隐私，可增删改。
  选词原则是「只可能出现在违规语境」，刻意不收「垃圾」「转账」「死」这类高频中性词，避免误报淹没复检队列。
- ⚠️ **同音变体必须收录**。实测 Whisper 会把「傻逼」转写成「**少逼**」（shǎ bī ↔ shǎo bī）；
  而流水线是 **DFA 命中才进 AI 复筛**，所以 DFA 漏检 = 该录音直接判 `COMPLIANT`、AI 永远看不到，
  是**静默假阴性**。词典里因此显式收录了 `少逼/傻比/傻壁/杀比/草你妈` 等 ASR 误写变体。
  维护词典时请按「ASR 可能怎么听错」补词，而不只是按「人会怎么写」。

## 运维与开发注意

- **卡住的录音**：`待处理 / 转写中 / 初筛中 / 复筛中` 停超过 2 分钟会标记「疑似卡住」，可用行内按钮手动推进。
- **服务重启自动恢复**：启动时会把上次中断在中间态的录音置为 `FAILED`（附原因「服务重启导致处理中断」），
  使其可被「重新处理」，不会永久卡死（见 `PipelineRecoveryService`）。
- **在途保护**：流水线运行期间重复点击「重新处理」会返回 400（该录音正在处理中）；任务由 `pipeline_tasks`
  持久化队列调度，服务重启后 RUNNING 任务会自动恢复续跑，不会重复调用 Whisper。
- **集成适配器**：多租户 / SSO / 流式 ASR / 说话人分离默认全部关闭、互不影响；多租户本轮只解析租户上下文
  （HEADER 模式缺失/非法租户头返回 400），**不做数据隔离**；SSO 与实时转写目前是适配器契约，
  真实对接步骤与回滚说明见 [docs/integrations.md](docs/integrations.md)。
- **切换复筛模型**：选择存在 `app_settings` 表，进程内不缓存，改完立即对新录音生效；配置里删掉某个供应商时，表里的旧选择会自动回退到 `APP_SCREEN_PROVIDER`（旧的模型名一并作废，避免拿旧模型名打新供应商）。密钥只从环境变量/配置读取，**不会**通过接口下发或写库。
- **Spring 代理坑**：`PipelineService` 有 `@Async` 方法，**不要让它实现接口**，否则 Spring 使用 JDK 动态代理，
  其它 Bean 按具体类型注入会启动失败（`was actually of type 'jdk.proxy2.$Proxy'`）。
  项目已在 `@EnableAsync(proxyTargetClass = true)` 上做了兜底。
- **演示音轨**：`tools/seed_demo_data.py --with-audio` 会为每条演示录音生成与其时长一致的静音音轨
  （#1001 额外生成 440Hz/660Hz 左右声道），因此演示数据上的「推进处理」也能真正跑通。

## 注意事项

- 转写使用本机 whisper.cpp，无 25MB 限制；上传上限由 `app.max-size-bytes` 控制（默认 200MB）。
- 语义复筛调用失败时（模型缺密钥 / 网络异常 / 额度不足），DFA 命中的录音会**降级转入人工复检**
  （`aiResultJson` 中 `degraded=true` 并带错误说明），不会漏审；恢复模型可用后重跑即可得到 AI 结论。
- 语料库初始含 5 条合成样例，随人工复检的积累会逐渐替换为真实标注数据。