package com.echolint.dto;

import java.time.LocalDate;

/**
 * AI 调用记录列表筛选条件（Batch 6，仅 ADMIN）。
 */
public record AiInvocationFilter(
        String stage,
        String providerId,
        String model,
        Boolean success,
        Long recordingId,
        LocalDate from,
        LocalDate to) {
}
