package com.echolint.service;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 一次 AI 调用的 token 用量与成本快照（Batch 3a）。
 *
 * <p>从兼容响应的 {@code usage.prompt_tokens / usage.completion_tokens} 提取；
 * 成本按**调用时**供应商单价计算并落库，后续改价不会改变历史账目。</p>
 *
 * @param inputTokens  输入 tokens（缺失/null = 未知）
 * @param outputTokens 输出 tokens（缺失/null = 未知）
 * @param costMicros   成本（微美元，1e-6 USD）；未配置单价时为 0
 */
public record AiUsage(Integer inputTokens, Integer outputTokens, Long costMicros) {

    private static final BigDecimal THOUSAND = BigDecimal.valueOf(1000);
    private static final BigDecimal MICROS_PER_USD = BigDecimal.valueOf(1_000_000);

    /**
     * 提取 usage；响应/字段缺失时返回 null 字段（null-safe，不抛异常）。
     */
    public static AiUsage extract(JsonNode response) {
        JsonNode usage = response == null ? null : response.get("usage");
        if (usage == null || !usage.isObject()) {
            return new AiUsage(null, null, null);
        }
        return new AiUsage(tokenOf(usage, "prompt_tokens"), tokenOf(usage, "completion_tokens"), null);
    }

    private static Integer tokenOf(JsonNode usage, String field) {
        JsonNode node = usage.get(field);
        if (node == null || !node.isNumber()) {
            return null;
        }
        return Math.max(0, node.asInt());
    }

    /**
     * 按供应商单价快照成本。单价为空/负数按 0 处理；tokens 缺失按 0 计算成本。
     */
    public AiUsage pricedWith(ScreenModelService.ActiveModel model) {
        if (model == null) {
            return new AiUsage(inputTokens, outputTokens, 0L);
        }
        return new AiUsage(inputTokens, outputTokens,
                costMicros(inputTokens, outputTokens, model.inputPricePer1k(), model.outputPricePer1k()));
    }

    /**
     * 成本计算：{@code tokens / 1000 * 单价(USD/1K tokens)}，四舍五入到微美元。
     */
    public static long costMicros(Integer inputTokens, Integer outputTokens,
                                  BigDecimal inputPricePer1k, BigDecimal outputPricePer1k) {
        BigDecimal cost = BigDecimal.valueOf(inputTokens == null ? 0 : inputTokens)
                .multiply(nonNegative(inputPricePer1k))
                .add(BigDecimal.valueOf(outputTokens == null ? 0 : outputTokens)
                        .multiply(nonNegative(outputPricePer1k)))
                // 先除以 1000 再转微美元；用 16 位精度避免多次舍入误差
                .divide(THOUSAND, 16, RoundingMode.HALF_UP)
                .multiply(MICROS_PER_USD);
        return cost.setScale(0, RoundingMode.HALF_UP).longValue();
    }

    private static BigDecimal nonNegative(BigDecimal price) {
        return price == null || price.signum() < 0 ? BigDecimal.ZERO : price;
    }
}
