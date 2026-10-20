package com.echolint.domain;

/**
 * 操作审计动作。审计写入失败不影响主流程（见 AuditService）。
 */
public enum AuditAction {
    LOGIN,
    LOGOUT,
    USER_CREATE,
    USER_UPDATE,
    USER_DISABLE,
    RECORDING_UPLOAD,
    RECORDING_DELETE,
    RECORDING_RETRY,
    RECORDING_CLAIM,
    RECORDING_ASSIGN,
    REVIEW_SUBMIT,
    REVIEW_REOPEN,
    REPORT_EXPORT,
    DICTIONARY_CREATE,
    DICTIONARY_UPDATE,
    DICTIONARY_DELETE,
    DICTIONARY_IMPORT,
    DICTIONARY_BULK_UPDATE,
    CORPUS_CREATE,
    CORPUS_DELETE,
    SETTINGS_UPDATE,
    /** 系统设置页发送 Webhook 测试通知（Batch 2b-2） */
    NOTIFY_TEST,
    /** AI 供应商管理（Batch 3a）：新增 / 修改 / 删除 / 连通性测试 */
    PROVIDER_CREATE,
    PROVIDER_UPDATE,
    PROVIDER_DELETE,
    PROVIDER_TEST,
    /** 异步任务队列（Batch 3b-1）：管理员手动重试死信/失败任务 */
    TASK_RETRY,
    /** 模型评测（Batch 3b-2）：管理员手动触发一次离线模型效果评测 */
    MODEL_EVALUATE,
    /** 开放 API 密钥（Batch 4a）：创建 / 停用 */
    API_KEY_CREATE,
    API_KEY_REVOKE,
    /** 开放 API 外部系统上传录音（actor 记为 api-key:<名称>） */
    OPEN_API_UPLOAD,
    /** 知识库 / SOP（Batch 4a）：新增 / 修改 / 删除 */
    KNOWLEDGE_CREATE,
    KNOWLEDGE_UPDATE,
    KNOWLEDGE_DELETE,
    /** RAG 检索增强（Batch 6）：设置变更 / 重建索引 / 对比评测 / 文档导入 */
    RAG_SETTINGS_UPDATE,
    RAG_REINDEX,
    RAG_EVALUATE,
    RAG_DOCUMENT_IMPORT
}
