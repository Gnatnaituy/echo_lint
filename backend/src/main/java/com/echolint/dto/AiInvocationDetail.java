package com.echolint.dto;

import com.echolint.entity.AiInvocation;

import java.time.LocalDateTime;

/**
 * AI 调用记录详情：包含完整请求体与模型原始回复（供「AI 对话记录」抽屉展示）。
 */
public record AiInvocationDetail(
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
        LocalDateTime createdAt,
        String requestJson,
        String responseJson) {

    public static AiInvocationDetail from(AiInvocation entity) {
        return new AiInvocationDetail(
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
                entity.getErrorMessage(),
                entity.getCreatedAt(),
                entity.getRequestJson(),
                entity.getResponseJson());
    }
}
