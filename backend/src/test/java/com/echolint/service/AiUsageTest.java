package com.echolint.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 用量提取与成本快照（Batch 3a）：usage 字段 null-safe、按单价计算、微美元四舍五入。
 */
class AiUsageTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private ScreenModelService.ActiveModel model(BigDecimal input, BigDecimal output) {
        return new ScreenModelService.ActiveModel(
                "deepseek", "DeepSeek", "https://api.deepseek.com", "deepseek-flash",
                "/chat/completions", true, true, null, Map.of(), 30, null, input, output);
    }

    private JsonNode json(String body) throws Exception {
        return objectMapper.readTree(body);
    }

    @Test
    void extractsPromptAndCompletionTokens() throws Exception {
        AiUsage usage = AiUsage.extract(json("{\"choices\":[],\"usage\":{\"prompt_tokens\":123,"
                + "\"completion_tokens\":45,\"total_tokens\":168}}"));

        assertEquals(123, usage.inputTokens());
        assertEquals(45, usage.outputTokens());
        assertNull(usage.costMicros(), "纯提取不计算成本");
    }

    @Test
    void missingOrNonNumericUsageIsNullSafe() throws Exception {
        AiUsage noUsage = AiUsage.extract(json("{\"choices\":[]}"));
        assertNull(noUsage.inputTokens());
        assertNull(noUsage.outputTokens());

        AiUsage weird = AiUsage.extract(json("{\"usage\":{\"prompt_tokens\":\"many\","
                + "\"completion_tokens\":null}}"));
        assertNull(weird.inputTokens());
        assertNull(weird.outputTokens());

        AiUsage nullResponse = AiUsage.extract(null);
        assertNull(nullResponse.inputTokens());
        assertNull(nullResponse.outputTokens());
    }

    @Test
    void negativeTokensAreClampedToZero() throws Exception {
        AiUsage usage = AiUsage.extract(json("{\"usage\":{\"prompt_tokens\":-5,\"completion_tokens\":-1}}"));

        assertEquals(0, usage.inputTokens());
        assertEquals(0, usage.outputTokens());
    }

    @Test
    void costUsesPricePerThousandTokens() throws Exception {
        AiUsage usage = AiUsage.extract(json("{\"usage\":{\"prompt_tokens\":1000,"
                + "\"completion_tokens\":500}}"))
                .pricedWith(model(new BigDecimal("0.0015"), new BigDecimal("0.002")));

        // 1000/1000*0.0015 + 500/1000*0.002 = 0.0015 + 0.001 = 0.0025 USD = 2500 微美元
        assertEquals(2500L, usage.costMicros());
        assertEquals(1000, usage.inputTokens());
        assertEquals(500, usage.outputTokens());
    }

    @Test
    void costRoundsToNearestMicroUsd() {
        // 1234/1000*0.0015 = 0.001851 USD = 1851 微美元
        assertEquals(1851L, AiUsage.costMicros(1234, 0, new BigDecimal("0.0015"), BigDecimal.ZERO));
        // 1 token * 0.0004 = 0.0000004 USD = 0.4 微美元 -> 四舍五入 0
        assertEquals(0L, AiUsage.costMicros(1, 0, new BigDecimal("0.0004"), BigDecimal.ZERO));
    }

    @Test
    void missingTokensOrPricesCostZero() {
        assertEquals(0L, AiUsage.costMicros(null, null, new BigDecimal("1"), new BigDecimal("1")));

        AiUsage usage = new AiUsage(null, null, null).pricedWith(model(
                new BigDecimal("0.01"), new BigDecimal("0.02")));
        assertEquals(0L, usage.costMicros());

        AiUsage nullPrices = new AiUsage(100, 100, null).pricedWith(model(null, null));
        assertEquals(0L, nullPrices.costMicros());
    }

    @Test
    void priceForNullModelCostsZero() {
        AiUsage usage = new AiUsage(100, 100, null).pricedWith(null);

        assertEquals(0L, usage.costMicros());
    }
}
