# 录音稽核平台 (Record Audit)

基于 **OpenAI Whisper** 的录音合规稽核服务：录音上传后自动完成 **转写 → DFA 初筛 → AI 语义复筛 →（命中则）人工复检**，人工复检结论自动回馈语料库，并从中挖掘新敏感词，形成自增长闭环。

## 总体流程

```
┌────────┐   ┌───────────────────┐   ┌──────────────┐   ┌───────────────────┐   ┌──────────────┐
│ 手动上传 │ → │ ① Whisper 英转文   │ → │ ② DFA 初筛    │ → │ ③ AI 语义复筛      │ → │ ④ 人工复检     │
│ (≤25MB) │   │   (whisper-1)     │   │  敏感词词典    │   │   (gpt-4o-mini)   │   │  confirm/reject│
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

- **DFA 初筛**：Aho-Corasick 自动机 + 字符跳变（`f u c k` / `f**k` 也能命中 `fuck`），大小写不敏感。
- **AI 复筛**：GPT-4o mini 结合上下文判断命中词是否构成真实违规，`few-shot` 自动注入人工复核语料。
- **人工复检**：初筛 + 复筛均判违规 → 进入复检队列；确认违规 / 判定误报。
- **语料回馈**：复检结论写入语料库（复筛时作为参考案例）；确认违规的录音由 AI 挖掘新敏感词入库（默认停用，人工审核后启用）。

## 技术栈

| 层 | 技术 |
|---|---|
| 后端 | Spring Boot 3.3 / Java 17, Spring Data JPA, WebClient, MySQL 8 |
| 前端 | Vue 3 + Vite + Element Plus + vue-router + axios |
| 部署 | docker-compose（MySQL + 后端 + Nginx 前端） |

## 目录结构

```
record-audit/
├── backend/                      # Spring Boot 后端
│   ├── src/main/java/com/recordaudit/
│   │   ├── config/               # 配置：OpenAI、异步流水线、CORS/静态资源
│   │   ├── controller/           # 录音/复检/词典/语料/统计 接口
│   │   ├── dfa/                  # DFA 匹配器（AC 自动机 + 词规范化）
│   │   ├── domain/               # 枚举（状态机/违规类型/严重度）
│   │   ├── entity/               # JPA 实体
│   │   ├── exception/            # 业务异常与全局异常处理
│   │   ├── repository/
│   │   └── service/              # 流水线/转写/复筛/复检/挖掘/语料/统计
│   ├── src/main/resources/       # application.yml + 预置词库 data.sql
│   └── src/test/                 # DFA 单元测试
├── frontend/                     # Vue3 前端（工作台/录音管理/人工复检/敏感词库/语料库）
├── docker-compose.yml            # 一键部署
└── .env.example                  # OPENAI_API_KEY 配置样例
```

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

## 本地开发

```bash
# 0. 准备 MySQL（compose 已把容器 3306 映射到宿主 3307，避免与本机 MySQL 冲突）
docker compose up -d mysql

# 1. 后端（通过 3307 连容器 MySQL；若本机 3306 空闲可改回默认 URL）
cd backend
export OPENAI_API_KEY=sk-...
export SPRING_DATASOURCE_URL='jdbc:mysql://localhost:3307/record_audit?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC&allowPublicKeyRetrieval=true&useSSL=false'
mvn spring-boot:run

# 2. 前端（开发服务器，/api 与 /uploads 代理到 8080）
cd frontend
npm install
npm run dev          # http://localhost:5173
```

### 环境变量（均有默认值）

| 变量 | 默认 | 说明 |
|---|---|---|
| `OPENAI_API_KEY` | (空) | OpenAI 密钥，缺失时转写/复筛会置录音为 FAILED |
| `OPENAI_BASE_URL` | `https://api.openai.com` | 可指向代理/网关 |
| `OPENAI_WHISPER_MODEL` | `whisper-1` | 转写模型 |
| `OPENAI_SCREEN_MODEL` | `gpt-4o-mini` | 语义复筛 + 词挖掘模型 |
| `SPRING_DATASOURCE_URL/USERNAME/PASSWORD` | localhost:3306, audit/audit123 | 数据库连接 |
| `APP_UPLOAD_DIR` | `data/uploads` | 录音文件存储目录 |

## API 一览

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/recordings/upload` | 上传录音（multipart `file`），异步启动稽核流水线 |
| GET | `/api/recordings?statuses=&keyword=&page=&size=` | 录音分页查询（statuses 逗号分隔） |
| GET | `/api/recordings/{id}` | 详情（转写/命中/复筛结果/复检信息） |
| GET | `/api/recordings/{id}/logs` | 处理轨迹日志 |
| POST | `/api/recordings/{id}/retry` | 失败重试 |
| DELETE | `/api/recordings/{id}` | 删除（含文件与日志） |
| POST | `/api/reviews/{id}` | 人工复检 `{result: CONFIRMED_VIOLATION\|FALSE_POSITIVE, violationType?, comment?}` |
| GET/POST/PUT/DELETE | `/api/dictionary...` | 敏感词 CRUD（变更后自动重建 DFA 匹配器） |
| POST | `/api/dictionary/test` | 检测工具：`{text}` → 命中列表 |
| GET/POST/DELETE | `/api/corpus...` | 语料库查询/录入/删除 |
| GET | `/api/corpus/export` | 语料导出 JSON |
| GET | `/api/stats/overview` | 工作台统计 |

## 录音状态机

```
PENDING → TRANSCRIBING → DFA_CHECKING → AI_CHECKING → NEEDS_REVIEW → VIOLATION_CONFIRMED
                                                      │            └───────→ FALSE_POSITIVE
                                                      └──→ COMPLIANT（任一步判定合规）
任意步骤异常 → FAILED（可在录音管理重试）
```

## 词典使用说明

- 词条保存时自动**规范化**：小写、去空格/标点（`Kill You` → `killyou`），匹配时原文中的空格/标点不影响命中。
- 匹配为**子串语义**（`unfuckingbelievable` 会命中 `fuck`），误报由 AI 复筛兜底——这是两段式设计的初衷。
- **AI 挖掘的新词默认停用**，需在敏感词库页人工审核后启用（列表按 `来源=AI 挖掘` 过滤）。
- 预置词典面向通用客服质检场景（辱骂/歧视/威胁/骚扰/色情/诈骗/隐私），可增删改。

## 注意事项

- Whisper 单文件上限 **25MB**（OpenAI 限制），上传接口同样限制；更长的录音请先切分或压缩。
- 语义复筛调用失败时，DFA 命中的录音会**降级转入人工复检**（aiResultJson 中带错误说明），不会漏审。
- 语料库初始含 5 条合成样例，随人工复检的积累会逐渐替换为真实标注数据。