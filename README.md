# EchoLint · 录音稽核平台

> **EchoLint** = Echo（录音） + Lint（自动化体检）：把每一通录音当作待检代码，先机械地查关键词，再让 AI 读语境，最后交给人拍板。

基于 **OpenAI Whisper** 的录音合规稽核服务：录音上传后自动完成 **转写 → DFA 初筛 → AI 语义复筛 →（命中则）人工复检**，人工复检结论自动回馈语料库，并从中挖掘新敏感词，形成自增长闭环。

## 总体流程

```
┌────────┐   ┌───────────────────┐   ┌──────────────┐   ┌───────────────────┐   ┌──────────────┐
│ 手动上传 │ → │ ① Whisper 英转文   │ → │ ② DFA 初筛    │ → │ ③ AI 语义复筛      │ → │ ④ 人工复检     │
│ (≤25MB) │   │   (whisper-1)     │   │  敏感词词典    │   │   (模型可切换)    │   │  confirm/reject│
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
  **模型可在「系统设置」页运行时切换**（OpenAI / DeepSeek / 任意 OpenAI 兼容网关），切换立即生效、重启后保持（详见下文）。
- **人工复检**：初筛 + 复筛均判违规 → 进入复检队列；确认违规 / 判定误报。复筛不可用时**降级转人工**，不漏审。
- **语料回馈**：复检结论写入语料库（复筛时作为参考案例）；确认违规的录音由 AI 挖掘新敏感词入库（默认停用，人工审核后启用）。

## 技术栈

| 层 | 技术 |
|---|---|
| 后端 | Spring Boot 3.3 / Java 17, Spring Data JPA, WebClient, MySQL 8 |
| AI | OpenAI（Whisper 转写 + 可选复筛）、DeepSeek（可选复筛 / 词挖掘），统一走 OpenAI 兼容协议；转写亦可换成本机 [whisper.cpp](https://github.com/ggml-org/whisper.cpp)（Metal 加速，录音不出本机） |
| 前端 | Vue 3 + Vite + Element Plus + vue-router + axios |
| 部署 | docker-compose（MySQL + 后端 + Nginx 前端） |

## 目录结构

```
echolint/
├── backend/                      # Spring Boot 后端
│   ├── src/main/java/com/echolint/
│   │   ├── config/               # 配置：OpenAI、异步流水线、CORS/静态资源
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
├── tools/seed_demo_data.py       # 演示数据生成/清理（预览 UI 用）
├── tools/whisper-server.sh       # 本机 whisper.cpp 转写服务（伪装成 OpenAI 端点）
├── docs/screenshots/             # 界面截图
├── docker-compose.yml            # 一键部署
└── .env.example                  # OPENAI_API_KEY 配置样例
```

## 管理端界面

统一的设计语言：深色侧栏 + 浅色内容区、6px 圆角体系、字重/字距收敛、状态用「圆点 + 文字」胶囊表达。

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
- **手动推进处理**：`待处理`、`处理失败`，以及停留超过 2 分钟的进行中记录，行内会出现「立即/推进处理」「重试」「重新处理」按钮（详情抽屉同样有），用于处理上传后服务重启、外部 API 失败等卡住的情况；已归档或已复检的记录不提供该按钮，避免覆盖既有结论。
- **人工复检**：左侧复检队列（可筛选）+ 右侧工作区（**逐句对照转写**、AI 判断置信度条、结论表单）；打开时**默认选中 AI 建议的违规类型**，提交后自动进入下一条。
- **逐句对照 + 音画联动**：每句话带起始时间、DFA 命中词高亮与标记胶囊（`命中 · xxx` / `AI 判违规原句`）；**点击句子跳转到对应音频时间点**，播放时**自动高亮并滚动到当前句**（可用「跟随播放」开关关闭），也可切回「全文」视图。
- **双栏对话 + 分声道试听**：双轨录音默认以左右双栏对话展示（左=坐席、右=客户），播放器可切「混合 / 坐席 / 客户」单独试听某一路。
- **敏感词库**：KPI 概览、DFA 检测工具（命中实时高亮预览）、词条表含命中次数条形图，AI 挖掘词带「待审核」标记。
- **报表**：日期范围 + 坐席/团队/方向筛选，汇总卡与按坐席/团队/违规类型统计，导出录音/违规/复核 CSV；敏感词库支持 CSV 导入导出与批量启停。
- **合规抽样与告警**：可按比例抽检 DFA 未命中的录音（界面显示「抽样」标记）；复核确认违规、流水线失败、待复核积压可推送 Webhook（支持 HMAC 签名），系统设置页可发送测试通知。
- **AI 治理与成本**：系统设置页可维护 AI 供应商（新增/编辑/停用/删除、密钥轮换与掩码展示、单价录入、连通性测试）；「成本看板」按日期/供应商/模型查看调用量、token 与预估成本。密钥以 AES-GCM 加密落库。
- **异步任务**：上传/重处理写入 `pipeline_tasks` 持久队列，失败自动退避重试（30/120/300s，最多 3 次），耗尽进死信并只在死信时告警；服务重启恢复 RUNNING 任务；「任务队列」页（ADMIN）可查看/重试。
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

### AI 复筛模型切换（OpenAI / DeepSeek）

语义复筛与敏感词挖掘都是 **OpenAI 兼容的 chat completions** 调用，所以「换模型」= 换 `base-url + api-key + model` 三件套。
系统把这套组合做成**供应商清单**，并在「系统设置」页支持**运行时切换**：立即生效、无需重启、重启后保持。

| 能力 | 说明 |
|---|---|
| 切换入口 | 「系统设置」页 → 选供应商 → 选/填模型 → 保存并生效（影响之后的所有复筛与挖掘，已出结论的历史录音不重判） |
| 持久化 | 写入 `app_settings` 表（`screen.provider` / `screen.model`）；表为空时回退到配置项 `APP_SCREEN_PROVIDER` |
| 连通性测试 | 可测「当前生效」或「待保存」的选择（先验证再切换），返回真实耗时与供应商报错原文 |
| 候选模型 | 下拉候选来自配置，同时支持**直接输入**列表外的新模型名 —— 供应商上新模型无需改配置重启 |
| 判定留痕 | AI 结论里记录 `screenProvider / screenModel`，录音详情与复检工作区显示「判定模型」，便于回溯与横向对比 |
| 不参与切换 | **Whisper 转写固定走 OpenAI**（chat 类供应商不提供转写能力）；DFA 初筛是本地算法，不调用外部模型 |
| 降级保护 | 当前模型缺密钥 / 网络失败 / 额度不足时，DFA 命中的录音**降级转人工复检**（`aiResultJson.degraded=true`），既不自动放行也不卡死 |

内置供应商（`application.yml` → `app.screen-models.providers`）：

| 供应商 | 默认模型 | 端点 | 候选模型 |
|---|---|---|---|
| OpenAI | `gpt-4o-mini` | `/v1/chat/completions` | `gpt-4o-mini` / `gpt-4o` / `gpt-4.1-mini` |
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

录音涉及客户隐私时，可以把转写从 OpenAI 换到**本机 whisper.cpp**：录音不出本机、无时长费用、无并发限制、也不受 25MB 限制。

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

> **零代码改动是怎么做到的**：whisper.cpp 的 server 只暴露 `/inference`（1.9.5 实测无 `/v1/audio/transcriptions`），脚本用 `--inference-path /v1/audio/transcriptions` 把它**改名**成 OpenAI 的路径。响应结构本来就和 OpenAI 对齐：`segments[].{start,end,text}` 同名同单位（秒），顶层也有 `language` / `duration` / `text`，因此 `TranscriptionService` 一行都不用改。
>
> 端口只绑 `127.0.0.1`；Docker Desktop 的 `host.docker.internal` 可以直达宿主回环，无需暴露到局域网。

> ⚠️ **转写配置用独立的 `whisper.*` 前缀，不是复用 `openai.*`**。因为 docker-compose 会注入空的 `OPENAI_API_KEY=""`，而环境变量按宽松绑定等价于 `openai.api-key`、**优先级高于 `application.yml`**，会把配置里解析好的值整个盖掉（这个问题在开发时真实踩到过）。

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
默认后端地址 `http://localhost:8082`、MySQL 容器 `echolint-mysql`，可用 `--api` / `--container` 覆盖。

## 快速开始（Docker 一键）

```bash
# 1. 配置 OpenAI 密钥（也可直接用环境变量）
cp .env.example .env
#   编辑 .env，填入 OPENAI_API_KEY=sk-...

# 2. 构建并启动（首次构建需数分钟）
docker compose up -d --build

# 3. 访问
#    前端:   http://localhost:8081   （后端 API 经 Nginx 同源代理）
#    后端:   http://localhost:8082   （健康检查 /actuator/health）

# 端口说明（宿主机）：前端 8081 → Nginx:80；后端 8082 → 8080；MySQL 3307 → 3306。
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

- 数据库结构由 **Flyway** 管理：`V1` 为基线标记（既有库自动 baseline 到 v1），后续变更从 `V2__*.sql` 开始；
  预置词典/语料由 `DataSeedRunner` 在 JPA 建表后幂等执行。
- 上传做 **SHA-256 去重**（重复文件 409，返回已存在录音 id）与 **magic bytes 校验**（内容与扩展名不符 400）；
  文件先写临时文件再原子移动，失败/重复自动清理。
- 容器日志已配置轮转（`max-size=10m, max-file=3`），支持优雅停机（`server.shutdown=graceful`）。

## 本地开发

```bash
# 0. 准备 MySQL（compose 已把容器 3306 映射到宿主 3307，避免与本机 MySQL 冲突）
docker compose up -d mysql

# 1. 后端（通过 3307 连容器 MySQL；若本机 3306 空闲可改回默认 URL）
cd backend
export OPENAI_API_KEY=sk-...
export SPRING_DATASOURCE_URL='jdbc:mysql://localhost:3307/echolint?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC&allowPublicKeyRetrieval=true&useSSL=false'
mvn spring-boot:run

# 2. 前端（开发服务器，/api 与 /uploads 默认代理到 8080；
#    若后端跑在别的端口，用 VITE_API_TARGET 覆盖，例如 compose 的 8082）
cd frontend
npm install
VITE_API_TARGET=http://localhost:8082 npm run dev
```

### 环境变量（均有默认值）

| 变量 | 默认 | 说明 |
|---|---|---|
| `OPENAI_API_KEY` | (空) | OpenAI 密钥；转写与复筛的兜底密钥 |
| `WHISPER_BASE_URL` | `https://api.openai.com` | **转写**端点，可指向本机 whisper.cpp（`http://host.docker.internal:9900`） |
| `WHISPER_API_KEY` | (空) | **转写**密钥，覆盖 `OPENAI_API_KEY`；自建服务填任意非空值 |
| `OPENAI_BASE_URL` | `https://api.openai.com` | 可指向代理/网关 |
| `OPENAI_WHISPER_MODEL` | `whisper-1` | 转写模型（自建 whisper.cpp 会忽略该值，但仍需带上） |
| `OPENAI_SCREEN_MODEL` | `gpt-4o-mini` | OpenAI 作为复筛模型时的默认模型 |
| `DEEPSEEK_API_KEY` | (空) | DeepSeek 密钥，用于复筛 / 词挖掘（切换前必须配置） |
| `DEEPSEEK_BASE_URL` | `https://api.deepseek.com` | DeepSeek 端点（可指向代理） |
| `DEEPSEEK_SCREEN_MODEL` | `deepseek-flash` | DeepSeek 默认模型 |
| `APP_SCREEN_PROVIDER` | `openai` | 首次启动（`app_settings` 表为空）时的复筛供应商 |
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
| `SPRING_DATASOURCE_URL/USERNAME/PASSWORD` | localhost:3306, audit/audit123 | 数据库连接 |
| `APP_UPLOAD_DIR` | `data/uploads` | 录音文件存储目录 |
| `APP_STEREO_ENABLED` | `true` | 是否启用双声道分轨处理 |
| `APP_STEREO_LEFT_SPEAKER` | `坐席` | 左声道说话人名称 |
| `APP_STEREO_RIGHT_SPEAKER` | `客户` | 右声道说话人名称 |

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
| GET | `/api/settings/notifications` | 抽样与 Webhook 通知配置（ADMIN，不返回密钥） |
| POST | `/api/settings/notifications/test` | 发送测试 Webhook 并返回结果（ADMIN） |
| GET/POST | `/api/settings/providers` | AI 供应商列表/新增（ADMIN，密钥只回掩码） |
| PUT/DELETE | `/api/settings/providers/{key}` | 修改（全量，apiKey 可选）/ 删除自定义供应商（ADMIN） |
| POST | `/api/settings/providers/{key}/test` | 供应商连通性测试（ADMIN） |
| GET | `/api/stats/ai-usage?from=&to=` | AI 调用量/成本统计（ADMIN，按日/供应商/模型） |
| GET | `/api/tasks?status=&recordingId=&page=&size=` | 异步稽核任务分页（ADMIN，size≤100） |
| POST | `/api/tasks/{id}/retry` | 重试死信/失败任务（ADMIN，审计 `TASK_RETRY`） |

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
- **在途保护**：流水线运行期间重复点击「重新处理」会被后端忽略（进程内 in-flight 标记），不会重复调用 Whisper。
- **切换复筛模型**：选择存在 `app_settings` 表，进程内不缓存，改完立即对新录音生效；配置里删掉某个供应商时，表里的旧选择会自动回退到 `APP_SCREEN_PROVIDER`（旧的模型名一并作废，避免拿旧模型名打新供应商）。密钥只从环境变量/配置读取，**不会**通过接口下发或写库。
- **Spring 代理坑**：`PipelineService` 有 `@Async` 方法，**不要让它实现接口**，否则 Spring 使用 JDK 动态代理，
  其它 Bean 按具体类型注入会启动失败（`was actually of type 'jdk.proxy2.$Proxy'`）。
  项目已在 `@EnableAsync(proxyTargetClass = true)` 上做了兜底。
- **演示音轨**：`tools/seed_demo_data.py --with-audio` 会为每条演示录音生成与其时长一致的静音音轨
  （#1001 额外生成 440Hz/660Hz 左右声道），因此演示数据上的「推进处理」也能真正跑通。

## 注意事项

- Whisper 单文件上限 **25MB**（OpenAI 限制），上传接口同样限制；更长的录音请先切分或压缩。
  **改用本机 whisper.cpp 后该限制即失效**（`app.max-size-bytes` 可调大，whisper.cpp 无此限制）。
- 语义复筛调用失败时（模型缺密钥 / 网络异常 / 额度不足），DFA 命中的录音会**降级转入人工复检**
  （`aiResultJson` 中 `degraded=true` 并带错误说明），不会漏审；恢复模型可用后重跑即可得到 AI 结论。
- 语料库初始含 5 条合成样例，随人工复检的积累会逐渐替换为真实标注数据。