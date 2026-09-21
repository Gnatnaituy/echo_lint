package com.echolint.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 人工复检请求
 */
public record ReviewRequest(
        @NotBlank(message = "复检结论不能为空") String result,
        String violationType,
        String comment,
        String reviewer) {
}