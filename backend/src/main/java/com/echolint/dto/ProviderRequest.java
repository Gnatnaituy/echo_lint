package com.echolint.dto;

import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;
import java.util.List;

/**
 * AI 供应商新增 / 修改请求（Batch 3a，仅 ADMIN）。
 *
 * <p>{@code key} 只在新增时使用（小写 slug，创建后不可修改）；修改时忽略。
 * {@code apiKey} 修改时留空/不传表示保持原密钥不变，传非空表示轮换。</p>
 */
public record ProviderRequest(
        String key,
        @NotBlank(message = "显示名称不能为空") String label,
        @NotBlank(message = "base-url 不能为空") String baseUrl,
        String chatPath,
        String defaultModel,
        List<String> models,
        Boolean jsonMode,
        Integer maxTokens,
        BigDecimal inputPricePer1k,
        BigDecimal outputPricePer1k,
        Boolean enabled,
        String apiKey) {
}
