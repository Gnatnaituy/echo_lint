package com.echolint.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 人工复检请求。
 *
 * <p>复核人取自当前登录用户（SecurityContext），请求体中即使带 reviewer 也会被忽略。</p>
 */
public record ReviewRequest(
        @NotBlank(message = "复检结论不能为空") String result,
        String violationType,
        String comment) {
}
