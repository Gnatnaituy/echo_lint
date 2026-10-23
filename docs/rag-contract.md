# RAG 接入契约（内部实现约定，供前后端/文档并行开发）

> 本文件是实现期的接口契约；面向用户的完整说明见 `docs/rag-integration.md`。
> 版本：Batch 6（PostgreSQL + pgvector + RAG）。所有接口默认仅登录用户可访问，写操作按现有 RBAC。

## 1. 配置（application.yml / 环境变量）

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/echolint
    username: audit
    password: audit123

app:
  rag:
    enabled: ${APP_RAG_ENABLED:false}          # 总开关；关闭时行为与旧版完全一致
    top-k: ${APP_RAG_TOP_K:4}                   # 知识库召回条数
    min-score: ${APP_RAG_MIN_SCORE:0.25}        # 余弦相似度下限（0~1）
    max-context-chars: ${APP_RAG_MAX_CONTEXT_CHARS:2400}  # 注入 prompt 的知识片段总字符上限
    corpus-examples: ${APP_RAG_CORPUS_EXAMPLES:5}         # 语义 few-shot 条数（A）
    embedding:
      provider-id: ${APP_RAG_EMBEDDING_PROVIDER:aliyun}   # 复用 ai_providers 的 base-url/api-key
      model: ${APP_RAG_EMBEDDING_MODEL:qwen3.7-text-embedding}
      path: ${APP_RAG_EMBEDDING_PATH:/embeddings}
      dimensions: ${APP_RAG_EMBEDDING_DIMENSIONS:1024}
      timeout-seconds: ${APP_RAG_EMBEDDING_TIMEOUT:30}
      batch-size: ${APP_RAG_EMBEDDING_BATCH_SIZE:16}
    chunk:
      chars: ${APP_RAG_CHUNK_CHARS:600}
      overlap: ${APP_RAG_CHUNK_OVERLAP:80}
    corpus:
      max-chars: ${APP_RAG_CORPUS_MAX_CHARS:6000}         # 语料 embedding 的截断长度
    index:
      pool-size: ${APP_RAG_INDEX_POOL:2}
      startup-backfill: ${APP_RAG_STARTUP_BACKFILL:true}
    documents:
      dir: ${APP_RAG_DOCUMENTS_DIR:data/knowledge-docs}
      max-bytes: ${APP_RAG_DOCUMENTS_MAX_BYTES:20971520}
      extensions: ${APP_RAG_DOCUMENTS_EXTENSIONS:pdf,docx,txt,md}
```

embedding 供应商复用 `ai_providers`（AES-GCM 密钥）；未配置/不可用时 RAG 自动降级为「不注入检索结果」，不阻塞复筛。

## 2. 数据模型（PostgreSQL + pgvector 0.8.x）

### knowledge_chunks（Flyway V2 建表；V3 调整 HNSW 为 1024 维）
| 列 | 类型 | 说明 |
|---|---|---|
| id | bigserial PK | |
| entry_id | bigint not null | knowledge_entries.id（应用层维护，无 FK） |
| chunk_index | int not null | 0 基 |
| content | text not null | 分块正文 |
| content_hash | varchar(64) not null | sha256(content) |
| embedding_text | text | `[0.1,0.2,...]` 文本形式；null 表示未索引 |
| embedding_model | varchar(128) | |
| embedding_dim | int | |
| created_at / updated_at | timestamp(6) not null | |

索引：`unique(entry_id, chunk_index)`、`(entry_id)`、HNSW 表达式索引
`((embedding_text::vector(1024)) vector_cosine_ops)`。

### knowledge_entries 新增列（Hibernate ddl-auto）
`source_type`(MANUAL/DOCUMENT, default MANUAL)、`source_filename`、`source_sha256`(unique, 允许多 NULL)、`source_size`、
`index_status`(PENDING/PARSING/INDEXING/READY/FAILED, default PENDING)、`index_error`(text)、`indexed_at`、`embedding_hash`(varchar64)、`chunk_count`(int, default 0)。

### corpus_entries 新增列
`embedding_text`(text)、`embedding_model`(varchar128)、`embedding_hash`(varchar64)、`embedding_updated_at`。

### ai_invocations
`recording_id` 改为可空（embedding 调用没有录音）；新增 stage：`EMBED_INDEX` / `EMBED_QUERY`。
成本看板沿用现有聚合，embedding token 自动计入。

## 3. REST API 契约

### 3.1 RAG 设置（ADMIN）
`GET /api/settings/rag`
```json
{
  "enabled": false,
  "source": "defaults",
  "embedding": {"providerId":"aliyun","providerLabel":"阿里云百炼","model":"qwen3.7-text-embedding",
                "path":"/embeddings","dimensions":1024,"timeoutSeconds":30,"apiKeyConfigured":true},
  "retrieval": {"topK":4,"minScore":0.25,"maxContextChars":2400,"corpusExamples":5},
  "index": {"knowledgeTotal":0,"knowledgeReady":0,"knowledgePending":0,"knowledgeFailed":0,
            "corpusTotal":0,"corpusReady":0,"lastReindexAt":null,"vectorAvailable":true}
}
```

`PUT /api/settings/rag`（ADMIN）
请求：`{"enabled":true,"topK":4,"minScore":0.25,"maxContextChars":2400,"corpusExamples":5}`
响应：同 GET。

`POST /api/settings/rag/test`（ADMIN）
响应：`{"ok":true,"providerLabel":"阿里云百炼","model":"qwen3.7-text-embedding","dimensions":1024,
"latencyMs":120,"message":"..."}`

`POST /api/settings/rag/reindex`（ADMIN）
请求：`{"scope":"knowledge"|"corpus"|"all"}`；响应：`{"queued":12,"skipped":3,"message":"..."}`（异步执行）。

`POST /api/settings/rag/evaluate`（ADMIN）
请求：`{"sampleSize":10}`（1~20）
响应：
```json
{
  "model":"gpt-4o-mini","sampleSize":10,
  "baseline":{"evaluated":10,"correct":7,"failed":0,"accuracy":0.7,"precision":0.8,"recall":0.6,
              "tp":3,"fp":1,"tn":3,"fn":2,"latencyMs":1200},
  "rag":{ "...同上..." },
  "delta":{"accuracy":0.1,"precision":0.0,"recall":0.2},
  "cases":[{"id":1,"label":"VIOLATION","baselinePrediction":"VIOLATION","ragPrediction":"VIOLATION",
            "baselineConfidence":0.9,"ragConfidence":0.95,"refs":["话术规范 A"],"error":null}]
}
```

### 3.2 知识库（登录可读；写 ADMIN/REVIEWER）
`GET /api/knowledge` 支持筛选参数：`keyword`（标题 / 正文 / 标签 / 来源文件名模糊匹配）、
`category`、`enabled`、`indexStatus`（精确，如 `READY`）、`sourceType`（`MANUAL` / `DOCUMENT`）、
`page`、`size`；响应为 Spring Page。条目新增字段：
`indexStatus`、`indexError`、`indexedAt`、`sourceType`、`sourceFilename`、`sourceSize`、`chunkCount`。
`embeddingHash`（标题 + 分类 + 正文指纹）在实体上标注 `@JsonIgnore`，**列表/详情不返回**，
只由下面的分块明细接口返回。

`GET /api/knowledge/{id}/chunks`（ADMIN）：管理端知识库页「索引明细」Tab 的分块下钻接口；
`page` 默认 0，`size` 默认 10（夹在 1~100）。条目不存在返回 404。
```json
{
  "entryId": 12,
  "title": "退款处理规范",
  "indexStatus": "READY",
  "indexError": null,
  "indexedAt": "2026-10-23T12:00:00",
  "embeddingHash": "……",
  "embeddingModel": "qwen3.7-text-embedding",
  "embeddingDimensions": 1024,
  "total": 7,
  "page": 0,
  "size": 10,
  "items": [
    {
      "chunkId": 101,
      "chunkIndex": 0,
      "content": "……",
      "contentHash": "……",
      "embeddingModel": "qwen3.7-text-embedding",
      "embeddingDim": 1024,
      "createdAt": "2026-10-23T12:00:00",
      "updatedAt": "2026-10-23T12:00:00"
    }
  ]
}
```
`embeddingModel` / `embeddingDimensions` 取该条目首个分块（`chunkIndex` 升序）的值，无分块时为 `null`；
`items` 按 `chunkIndex` 升序分页返回。该接口只读、仅 ADMIN 可访问，不写审计。

`POST /api/knowledge` / `PUT /api/knowledge/{id}`：响应同 GET；保存后异步重建该条索引。
`DELETE /api/knowledge/{id}`：同时删除 chunks。
`POST /api/knowledge/{id}/reindex`（ADMIN/REVIEWER）：响应 `{"id":1,"status":"PENDING"}`。

`POST /api/knowledge/documents`（ADMIN/REVIEWER，multipart/form-data）
字段：`file`（必填）、`title`（可选）、`category`（可选）、`enabled`（可选）。
响应：201 + 条目对象（`indexStatus=PENDING`，异步解析+索引）；重复文档 409。

`POST /api/knowledge/search`（ADMIN/REVIEWER）
请求：`{"query":"...","topK":4}`；响应：
```json
{"query":"...","results":[{"chunkId":1,"entryId":2,"title":"话术规范","category":"SOP",
"content":"...","score":0.83}]}
```

### 3.3 复筛结果留痕
`recordings.aiResultJson` 增加：
`"rag":{"enabled":true,"status":"ok|empty|degraded","refs":[{"id":1,"title":"...","score":0.83}]}`。
完整检索上下文已包含在 `ai_invocations.request_json` 的 user prompt 中（现有 AI 留痕抽屉可直接看到）。

## 4. 行为约定

- `app.rag.enabled=false`：不发起任何 embedding 调用，复筛行为与旧版一致（回归测试覆盖）。
- 检索失败/embedding 不可用：继续复筛，仅不带检索结果，`rag.status=degraded`，写 WARN 日志。
- 知识库写入后异步索引；失败置 `FAILED` 存 `index_error`，不阻塞 CRUD；可手动重建。
- 复筛 prompt 预算：`max-context-chars` 裁剪；每条引用格式 `【知识库·分类】标题：正文`。
- 语料 few-shot（A）：优先语义召回（违规/合规各半），不足时用最新样本补齐；失败回退旧逻辑。
- 文档导入（D）：pdf/docx/txt/md；校验扩展名+魔数+大小；解析失败置 FAILED 并保留错误。
