package com.echolint.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 敏感词录入/编辑请求
 */
public record DictWordRequest(
        @NotBlank(message = "词不能为空") String word,
        String category,
        String severity,
        Boolean enabled) {
}