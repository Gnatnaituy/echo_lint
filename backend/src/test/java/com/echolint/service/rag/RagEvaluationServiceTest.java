package com.echolint.service.rag;

import com.echolint.config.ScreenModelProperties;
import com.echolint.domain.CorpusLabel;
import com.echolint.entity.CorpusEntry;
import com.echolint.exception.BizException;
import com.echolint.repository.CorpusEntryRepository;
import com.echolint.service.AiModelCaller;
import com.echolint.service.AiQuotaService;
import com.echolint.service.ModelEvaluationService;
import com.echolint.service.ScreenModelService;
import com.echolint.service.ScreeningPromptSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Phase 0：with/without RAG 对比评测的指标计算与边界。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RagEvaluationServiceTest {

    private static final String VIOLATION = "{\"violation\":true,\"violation_type\":\"INSULT\","
            + "\"reason\":\"insult\",\"confidence\":0.9,\"target_sentence\":\"idiot\"}";
    private static final String COMPLIANT = "{\"violation\":false,\"violation_type\":\"NONE\","
            + "\"reason\":\"no violation\",\"confidence\":0.9,\"target_sentence\":\"\"}";

    @Mock private CorpusEntryRepository corpusRepository;
    @Mock private ModelEvaluationService modelEvaluationService;
    @Mock private ScreenModelService screenModelService;
    @Mock private AiModelCaller modelCaller;
    @Mock private AiQuotaService quotaService;
    @Mock private RagRetrievalService retrievalService;
    @Mock private RagEmbeddingClient embeddingClient;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Deque<JsonNode> replies = new ArrayDeque<>();
    private RagEvaluationService service;

    @BeforeEach
    void setUp() {
        ScreenModelProperties properties = new ScreenModelProperties();
        ScreeningPromptSupport promptSupport = new ScreeningPromptSupport(properties, objectMapper);
        service = new RagEvaluationService(corpusRepository, modelEvaluationService, screenModelService,
                promptSupport, modelCaller, quotaService, retrievalService, embeddingClient);

        ScreenModelService.ActiveModel model = new ScreenModelService.ActiveModel(
                "stub", "Stub", "https://stub", "stub-model", "/chat/completions", true, true,
                null, Map.of(), 30, null, new BigDecimal("0.001"), new BigDecimal("0.002"));
        when(screenModelService.active()).thenReturn(model);
        when(embeddingClient.available()).thenReturn(true);
        when(modelCaller.call(any(), anyString(), anyString(), anyLong())).thenAnswer(invocation -> {
            JsonNode reply = replies.poll();
            if (reply == null) {
                throw new IllegalStateException("测试没有预置足够的模型回复");
            }
            return reply;
        });
        lenient().when(retrievalService.knowledgeContext(anyString(), isNull(), eq(true)))
                .thenReturn(RagContext.ok(List.of(new RagReference(1L, 2L, "话术规范", "SOP",
                        "不要辱骂客户", 0.88))));
    }

    private JsonNode reply(String content) {
        ObjectNode root = objectMapper.createObjectNode();
        root.putArray("choices").addObject().putObject("message").put("content", content);
        root.putObject("usage").put("prompt_tokens", 100).put("completion_tokens", 50);
        return root;
    }

    private static CorpusEntry sample(long id, CorpusLabel label, String transcript) {
        CorpusEntry entry = new CorpusEntry();
        entry.setId(id);
        entry.setLabel(label);
        entry.setTranscript(transcript);
        return entry;
    }

    @Test
    void computesBaselineAndRagMetricsWithCases() {
        when(modelEvaluationService.loadBalancedSamples(10)).thenReturn(List.of(
                sample(1L, CorpusLabel.VIOLATION, "you idiot"),
                sample(2L, CorpusLabel.COMPLIANT, "have a nice day")));
        replies.add(reply(VIOLATION));   // baseline #1 → TP
        replies.add(reply(VIOLATION));   // rag #1 → TP
        replies.add(reply(COMPLIANT));   // baseline #2 → TN
        replies.add(reply(VIOLATION));   // rag #2 → FP

        RagEvaluationService.EvaluationResult result = service.evaluate(10);

        assertEquals(2, result.sampleSize());
        assertEquals(2, result.baseline().evaluated());
        assertEquals(1.0, result.baseline().accuracy(), 1e-9);
        assertEquals(1.0, result.baseline().precision(), 1e-9);
        assertEquals(2, result.rag().evaluated());
        assertEquals(0.5, result.rag().accuracy(), 1e-9);
        assertEquals(0.5, result.rag().precision(), 1e-9);
        assertEquals(1.0, result.rag().recall(), 1e-9);
        assertEquals(-0.5, result.delta().accuracy(), 1e-9);
        assertEquals(2, result.cases().size());
        assertEquals("VIOLATION", result.cases().get(0).baselinePrediction());
        assertEquals("VIOLATION", result.cases().get(0).ragPrediction());
        assertEquals(List.of("话术规范"), result.cases().get(0).references());
        assertNull(result.cases().get(0).error());
    }

    @Test
    void failedCallsCountedPerSideWithoutAborting() {
        when(modelEvaluationService.loadBalancedSamples(5)).thenReturn(
                List.of(sample(1L, CorpusLabel.VIOLATION, "idiot")));
        replies.add(reply("not json"));      // baseline 解析失败
        replies.add(reply(VIOLATION));       // rag 成功

        RagEvaluationService.EvaluationResult result = service.evaluate(5);

        assertEquals(0, result.baseline().evaluated());
        assertEquals(1, result.baseline().failed());
        assertEquals(1, result.rag().evaluated());
        assertEquals(1, result.rag().correct());
        assertNotNull(result.cases().get(0).error());
    }

    @Test
    void rejectsWhenEmbeddingUnavailable() {
        when(embeddingClient.available()).thenReturn(false);

        BizException error = assertThrows(BizException.class, () -> service.evaluate(10));
        assertTrue(error.getMessage().contains("embedding"));
    }

    @Test
    void rejectsWhenNoSamples() {
        when(modelEvaluationService.loadBalancedSamples(10)).thenReturn(List.of());

        BizException error = assertThrows(BizException.class, () -> service.evaluate(10));
        assertTrue(error.getMessage().contains("语料库暂无"));
    }

    @Test
    void clampsSampleSize() {
        when(modelEvaluationService.loadBalancedSamples(20)).thenReturn(List.of());

        assertThrows(BizException.class, () -> service.evaluate(100));
        org.mockito.Mockito.verify(modelEvaluationService).loadBalancedSamples(20);
    }
}
