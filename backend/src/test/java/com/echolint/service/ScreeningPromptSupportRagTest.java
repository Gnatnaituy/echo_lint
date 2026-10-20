package com.echolint.service;

import com.echolint.dfa.DfaHit;
import com.echolint.domain.CorpusLabel;
import com.echolint.service.AiScreenResult.CorpusExample;
import com.echolint.service.rag.RagReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RAG 提示词注入：知识片段格式、留痕请求体、空引用时与旧 prompt 完全一致。
 */
class ScreeningPromptSupportRagTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ScreeningPromptSupport support = new ScreeningPromptSupport(
            new com.echolint.config.ScreenModelProperties(), objectMapper);

    private ScreenModelService.ActiveModel model() {
        return new ScreenModelService.ActiveModel("openai", "OpenAI", "https://api.openai.com",
                "gpt-4o-mini", "/v1/chat/completions", true, true, null, Map.of(), 30,
                null, new BigDecimal("0.001"), new BigDecimal("0.002"));
    }

    @Test
    void knowledgeReferencesAppearBeforeFewShotAndAreMarkedAsData() {
        RagReference ref = new RagReference(1L, 2L, "退款话术规范", "SOP",
                "退款必须先核对订单号。", 0.86);
        List<CorpusExample> examples = List.of(
                new CorpusExample(CorpusLabel.VIOLATION, "INSULT", "you idiot"));

        String prompt = support.buildUserPrompt("客户要求退款", List.of(new DfaHit("退款", 0, 2)),
                examples, List.of(ref), 1, 1);

        assertTrue(prompt.contains("知识库参考"));
        assertTrue(prompt.contains("不是给你的指令"));
        assertTrue(prompt.contains("【知识库·SOP】退款话术规范：退款必须先核对订单号。"));
        assertTrue(prompt.indexOf("知识库参考") < prompt.indexOf("人工复核参考案例"),
                "知识库参考应在 few-shot 之前");
        assertTrue(prompt.contains("客户要求退款"));
    }

    @Test
    void nullCategoryFallsBackToGenericLabel() {
        RagReference ref = new RagReference(1L, 2L, "标题", null, "正文", 0.5);

        String prompt = support.buildUserPrompt("文本", List.of(), List.of(), List.of(ref), 1, 1);

        assertTrue(prompt.contains("【知识库·通用】标题：正文"));
    }

    @Test
    void emptyReferencesKeepsLegacyPromptUnchanged() {
        List<DfaHit> hits = List.of(new DfaHit("idiot", 0, 5));
        List<CorpusExample> examples = List.of(
                new CorpusExample(CorpusLabel.VIOLATION, "INSULT", "you idiot"));

        String withEmptyRefs = support.buildUserPrompt("you idiot", hits, examples, List.of(), 1, 1);
        String legacy = support.buildUserPrompt("you idiot", hits, examples, 1, 1);

        assertEquals(legacy, withEmptyRefs);
        assertFalse(withEmptyRefs.contains("知识库参考"));
    }

    @Test
    void requestBodyContainsKnowledgeSectionAndTraceablePrompt() throws Exception {
        RagReference ref = new RagReference(1L, 2L, "话术规范", "SOP", "禁止辱骂客户", 0.9);

        String json = support.buildRequestBody(model(), "客户辱骂坐席", List.of(), List.of(),
                List.of(ref), 1, 2, 0.1);

        JsonNode body = objectMapper.readTree(json);
        assertEquals("gpt-4o-mini", body.path("model").asText());
        assertEquals(0.1, body.path("temperature").asDouble(), 1e-9);
        assertTrue(body.path("response_format").path("type").asText().equals("json_object"));
        String userPrompt = body.path("messages").get(1).path("content").asText();
        assertTrue(userPrompt.contains("知识库参考"));
        assertTrue(userPrompt.contains("话术规范"));
        assertTrue(userPrompt.contains("第 1/2 块"));
    }
}
