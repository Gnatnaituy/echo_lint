package com.recordaudit.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 人工录入语料请求
 */
public record CorpusRequest(
        @NotBlank(message = "语料文本不能为空") String transcript,
        @NotBlank(message = "标注不能为空") String label,
        String violationType,
        String reason) {
}