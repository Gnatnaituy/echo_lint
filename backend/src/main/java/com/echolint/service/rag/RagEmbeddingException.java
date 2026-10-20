package com.echolint.service.rag;

/**
 * RAG embedding 调用异常（网络失败、密钥缺失、返回格式/维度不合法等）。
 *
 * <p>上层（复筛检索）捕获后降级为「不注入检索结果」；索引任务捕获后把条目标记 FAILED。</p>
 */
public class RagEmbeddingException extends RuntimeException {

    public RagEmbeddingException(String message) {
        super(message);
    }

    public RagEmbeddingException(String message, Throwable cause) {
        super(message, cause);
    }
}
