package com.echolint.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 切换 AI 复筛模型请求
 *
 * @param providerId 供应商标识（openai / deepseek / …）
 * @param model      模型名；留空则使用该供应商的 default-model
 */
public record ScreenModelRequest(
        @NotBlank(message = "供应商不能为空") String providerId,
        String model) {
}
