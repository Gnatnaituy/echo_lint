package com.echolint.dto;

import java.time.LocalDateTime;

/**
 * 开放 API 录音状态视图（Batch 4a）：只暴露状态类字段，不返回转写全文、AI 原文等敏感内容。
 */
public record OpenRecordingView(
        Long id,
        String status,
        String fileName,
        LocalDateTime uploadTime,
        LocalDateTime processedTime,
        Integer durationSeconds,
        Integer hitCount,
        String violationType,
        String reviewResult,
        String errorMessage) {
}
