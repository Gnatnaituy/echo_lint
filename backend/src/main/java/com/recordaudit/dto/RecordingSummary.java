package com.recordaudit.dto;

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
        String violationType,
        String violationTypeLabel,
        ReviewResult reviewResult,
        String reviewComment,
        String transcriptSnippet,
        String errorMessage,
        LocalDateTime uploadTime,
        LocalDateTime processedTime) {

    public static RecordingSummary from(com.recordaudit.entity.Recording r) {
        String snippet = r.getTranscript();
        if (snippet != null && snippet.length() > 300) {
            snippet = snippet.substring(0, 300) + "...";
        }
        return new RecordingSummary(
                r.getId(),
                r.getFileName(),
                r.getDurationSeconds(),
                r.getStatus(),
                r.getHitCount() == null ? 0 : r.getHitCount(),
                r.getViolationType(),
                r.getViolationTypeLabel(),
                r.getReviewResult(),
                r.getReviewComment(),
                snippet,
                r.getErrorMessage(),
                r.getUploadTime(),
                r.getProcessedTime());
    }
}