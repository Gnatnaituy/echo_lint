package com.echolint.dto;

import com.echolint.entity.AiInvocation;

import java.time.LocalDateTime;

/**
 * AI 调用记录列表项：刻意不包含 requestJson/responseJson，避免列表响应过大；
 * 完整提示词与模型回复通过 {@code GET /api/ai-invocations/{id}} 查看。
 */
public record AiInvocationSummary(
        Long id,
        Long recordingId,
        String stage,
        String providerId,
        String providerLabel,
        String model,
        String endpoint,
        Boolean success,
        Boolean degraded,
        Integer latencyMs,
        Integer requestChars,
        Integer responseChars,
        Integer inputTokens,
        Integer outputTokens,
        Long costMicros,
        String errorMessage,
        LocalDateTime createdAt) {

    public static AiInvocationSummary from(AiInvocation entity) {
        return new AiInvocationSummary(
                entity.getId(),
                entity.getRecordingId(),
                entity.getStage(),
                entity.getProviderId(),
                entity.getProviderLabel(),
                entity.getModel(),
                entity.getEndpoint(),
                entity.getSuccess(),
                entity.getDegraded(),
                entity.getLatencyMs(),
                entity.getRequestChars(),
                entity.getResponseChars(),
                entity.getInputTokens(),
                entity.getOutputTokens(),
                entity.getCostMicros(),
                truncate(entity.getErrorMessage(), 300),
                entity.getCreatedAt());
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
