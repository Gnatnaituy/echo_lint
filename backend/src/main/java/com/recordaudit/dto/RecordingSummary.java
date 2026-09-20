package com.recordaudit.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recordaudit.domain.RecordingStatus;
import com.recordaudit.domain.ReviewResult;

import java.time.LocalDateTime;

/**
 * 录音列表摘要（不含全文，前端按需加载详情）
 */
public record RecordingSummary(
        Long id,
        String fileName,
        Integer durationSeconds,
        RecordingStatus status,
        int hitCount,
        /** 声道数：2 = 双声道双轨（坐席/客户各一路） */
        Integer channelCount,
        /** 人工复检确认的违规类型 */
        String violationType,
        String violationTypeLabel,
        /** AI 语义复筛判定：null 表示尚未产出结果 */
        Boolean aiViolation,
        String aiViolationTypeLabel,
        Double aiConfidence,
        ReviewResult reviewResult,
        String reviewComment,
        String reviewer,
        LocalDateTime reviewTime,
        String transcriptSnippet,
        String errorMessage,
        LocalDateTime uploadTime,
        LocalDateTime processedTime) {

    public static RecordingSummary from(com.recordaudit.entity.Recording r, ObjectMapper objectMapper) {
        String snippet = r.getTranscript();
        if (snippet != null && snippet.length() > 300) {
            snippet = snippet.substring(0, 300) + "...";
        }

        Boolean aiViolation = null;
        String aiTypeLabel = null;
        Double aiConfidence = null;
        if (r.getAiResultJson() != null && !r.getAiResultJson().isBlank()) {
            try {
                JsonNode node = objectMapper.readTree(r.getAiResultJson());
                if (node.path("violation").isBoolean()) {
                    aiViolation = node.path("violation").asBoolean();
                }
                // 仅在判定违规时展示类型标签，NONE/"无" 不作为类型
                String typeCode = node.path("violationType").asText("");
                if (!"NONE".equalsIgnoreCase(typeCode)) {
                    String label = node.path("violationTypeLabel").asText("");
                    aiTypeLabel = label.isBlank() ? null : label;
                }
                if (node.path("confidence").isNumber()) {
                    aiConfidence = node.path("confidence").asDouble();
                }
            } catch (Exception ignored) {
                // 结果 JSON 异常时按“无 AI 结果”处理，不影响列表展示
            }
        }

        return new RecordingSummary(
                r.getId(),
                r.getFileName(),
                r.getDurationSeconds(),
                r.getStatus(),
                r.getHitCount() == null ? 0 : r.getHitCount(),
                r.getChannelCount(),
                r.getViolationType(),
                r.getViolationTypeLabel(),
                aiViolation,
                aiTypeLabel,
                aiConfidence,
                r.getReviewResult(),
                r.getReviewComment(),
                r.getReviewer(),
                r.getReviewTime(),
                snippet,
                r.getErrorMessage(),
                r.getUploadTime(),
                r.getProcessedTime());
    }
}
