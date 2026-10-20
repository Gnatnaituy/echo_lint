package com.echolint.service.rag;

import java.util.List;

/**
 * 一次知识库检索的结果状态（RAG Phase B）。
 *
 * <p>status 取值：{@code disabled}（RAG 开关关闭）、{@code ok}、{@code empty}（检索成功但无高于阈值的结果）、
 * {@code degraded}（embedding/检索失败，已降级为不注入，不影响复筛）。</p>
 */
public record RagContext(boolean enabled, String status, List<RagReference> references, String error) {

    public static final String STATUS_DISABLED = "disabled";
    public static final String STATUS_OK = "ok";
    public static final String STATUS_EMPTY = "empty";
    public static final String STATUS_DEGRADED = "degraded";

    public RagContext {
        references = references == null ? List.of() : List.copyOf(references);
    }

    public static RagContext disabled() {
        return new RagContext(false, STATUS_DISABLED, List.of(), null);
    }

    public static RagContext empty() {
        return new RagContext(true, STATUS_EMPTY, List.of(), null);
    }

    public static RagContext ok(List<RagReference> references) {
        return references == null || references.isEmpty()
                ? empty()
                : new RagContext(true, STATUS_OK, references, null);
    }

    public static RagContext degraded(String error) {
        return new RagContext(true, STATUS_DEGRADED, List.of(), error);
    }

    public boolean hasReferences() {
        return !references.isEmpty();
    }

    public boolean degraded() {
        return STATUS_DEGRADED.equals(status);
    }
}
