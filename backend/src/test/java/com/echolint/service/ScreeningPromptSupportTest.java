package com.echolint.service;

import com.echolint.config.ScreenModelProperties;
import com.echolint.dfa.DfaHit;
import com.echolint.domain.CorpusLabel;
import com.echolint.exception.BizException;
import com.echolint.service.AiScreenResult.CorpusExample;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 复筛提示词与严格解析（Batch 3b-2 从 SemanticScreeningService 抽出，评测复用）：
 * 请求体结构、温度参数、无 DFA 命中/few-shot 时的降级文案、严格解析与失败语义。
 */
class ScreeningPromptSupportTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ScreenModelProperties properties = new ScreenModelProperties();
    private final ScreeningPromptSupport support = new ScreeningPromptSupport(properties, objectMapper);

    private ScreenModelService.ActiveModel model() {
        return new ScreenModelService.ActiveModel(
                "deepseek", "DeepSeek", "https://api.deepseek.com", "deepseek-flash",
                "/chat/completions", true, true, 2048, Map.of(), 30, null,
                BigDecimal.ZERO, BigDecimal.ZERO);
    }

    private JsonNode parse(String requestJson) throws Exception {
        return objectMapper.readTree(requestJson);
    }

    @Test
    void requestBodyContainsSystemPromptAndUserPrompt() throws Exception {
        String requestJson = support.buildRequestBody(model(), "you idiot", List.of(),
                List.of(), 1, 1, 0);

        JsonNode body = parse(requestJson);
        assertEquals("deepseek-flash", body.path("model").asText());
        assertEquals(0, body.path("temperature").asDouble(), 1e-9);
        assertTrue(body.path("response_format").path("type").asText().equals("json_object"));
        assertEquals(2048, body.path("max_tokens").asInt());
        JsonNode messages = body.path("messages");
        assertEquals(2, messages.size());
        assertEquals("system", messages.path(0).path("role").asText());
        assertTrue(messages.path(0).path("content").asText().contains("合规审查助手"));
        assertTrue(messages.path(0).path("content").asText().contains("violation"));
        assertEquals("user", messages.path(1).path("role").asText());
        String userPrompt = messages.path(1).path("content").asText();
        assertTrue(userPrompt.contains("DFA 词典初筛命中的关键词"), userPrompt);
        assertTrue(userPrompt.contains("（无）"), "无 DFA 命中应显示（无）");
        assertTrue(userPrompt.contains("you idiot"));
        assertFalse(userPrompt.contains("few-shot"), "评测/未命中场景不应注入参考案例");
    }

    @Test
    void userPromptIncludesHitsAndFewShotExamplesWhenProvided() throws Exception {
        List<DfaHit> hits = List.of(new DfaHit("idiot", 0, 5), new DfaHit("idiot", 10, 15));
        List<CorpusExample> examples = List.of(
                new CorpusExample(CorpusLabel.VIOLATION, "INSULT", "you are an idiot"),
                new CorpusExample(CorpusLabel.COMPLIANT, null, "have a nice day"));

        String userPrompt = parse(support.buildRequestBody(model(), "you idiot", hits, examples, 1, 1, 0.1))
                .path("messages").path(1).path("content").asText();

        assertTrue(userPrompt.contains("idiot"), "命中词应去重后列出");
        assertEquals(3, userPrompt.split("idiot", -1).length - 1,
                "命中词去重后只占参考行一次（另两次分别来自 few-shot 与转写文本）");
        assertTrue(userPrompt.contains("违规(INSULT)"));
        assertTrue(userPrompt.contains("【合规】"));
    }

    @Test
    void multiChunkPromptMentionsChunkIndex() throws Exception {
        String userPrompt = parse(support.buildRequestBody(model(), "chunk", List.of(), List.of(), 2, 3, 0))
                .path("messages").path(1).path("content").asText();

        assertTrue(userPrompt.contains("第 2/3 块"), userPrompt);
    }

    @Test
    void parseReturnsStrictVerdictWithVariant() {
        AiScreenResult result = support.parseResult(
                "{\"violation\":true,\"violation_type\":\"THREAT\",\"reason\":\"threat\","
                        + "\"confidence\":0.87,\"target_sentence\":\"I will hurt you\"}",
                model(), "B", 1);

        assertTrue(result.violation());
        assertEquals("THREAT", result.typeCode());
        assertEquals(0.87, result.confidence(), 1e-9);
        assertEquals("I will hurt you", result.targetSentence());
        assertEquals("B", result.screenVariant());
        assertEquals("deepseek", result.providerId());
        assertFalse(result.degraded());
    }

    @Test
    void parseToleratesNullVariantForEvaluation() {
        AiScreenResult result = support.parseResult(
                "{\"violation\":false,\"reason\":\"ok\",\"confidence\":0.5}", model(), null, 1);

        assertNull(result.screenVariant());
        assertFalse(result.violation());
    }

    @Test
    void parseRejectsInvalidPayloads() {
        assertThrows(BizException.class, () -> support.parseResult("not json", model(), "A", 1));
        assertThrows(BizException.class,
                () -> support.parseResult("{\"violation_type\":\"INSULT\"}", model(), "A", 1));
        assertThrows(BizException.class,
                () -> support.parseResult("{\"violation\":\"true\"}", model(), "A", 1));
        assertThrows(BizException.class, () -> support.parseResult("[]", model(), "A", 1));
        assertThrows(BizException.class, () -> support.parseResult(null, model(), "A", 1));
    }

    @Test
    void temperatureComesFromParameterNotConfig() throws Exception {
        properties.setTemperature(0.1);

        String evalJson = support.buildRequestBody(model(), "text", List.of(), List.of(), 1, 1, 0);
        String prodJson = support.buildRequestBody(model(), "text", List.of(), List.of(), 1, 1,
                support.productionTemperature());

        assertEquals(0, parse(evalJson).path("temperature").asDouble(), 1e-9);
        assertEquals(0.1, parse(prodJson).path("temperature").asDouble(), 1e-9);
    }
}
