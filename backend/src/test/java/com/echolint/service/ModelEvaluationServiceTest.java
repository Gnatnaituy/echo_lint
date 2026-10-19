package com.echolint.service;

import com.echolint.config.ScreenModelProperties;
import com.echolint.domain.CorpusLabel;
import com.echolint.entity.CorpusEntry;
import com.echolint.exception.BizException;
import com.echolint.repository.CorpusEntryRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 模型评测（Batch 3b-2）：混淆矩阵与 accuracy/precision/recall、均衡抽样与回填、
 * 失败样本不参与分母、样本数封顶、配额超限中止、空语料/未知供应商报错。
 */
@ExtendWith(MockitoExtension.class)
class ModelEvaluationServiceTest {

    @Mock
    private CorpusEntryRepository corpusRepository;
    @Mock
    private ScreenModelService screenModelService;
    @Mock
    private AiModelCaller modelCaller;
    @Mock
    private AiQuotaService quotaService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ModelEvaluationService service;

    @BeforeEach
    void setUp() {
        ScreenModelProperties properties = new ScreenModelProperties();
        ScreeningPromptSupport promptSupport = new ScreeningPromptSupport(properties, objectMapper);
        service = new ModelEvaluationService(corpusRepository, screenModelService, promptSupport,
                modelCaller, quotaService);
        lenient().when(screenModelService.resolveModel("stub", "stub-model")).thenReturn(model());
    }

    private ScreenModelService.ActiveModel model() {
        return new ScreenModelService.ActiveModel(
                "stub", "Stub", "https://stub.example.com", "stub-model", "/v1/chat/completions",
                true, true, null, Map.of(), 30, null, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    private CorpusEntry corpus(long id, CorpusLabel label, String transcript) {
        CorpusEntry entry = new CorpusEntry();
        entry.setId(id);
        entry.setLabel(label);
        entry.setTranscript(transcript);
        return entry;
    }

    private void stubSamples(List<CorpusEntry> violations, List<CorpusEntry> compliant) {
        when(corpusRepository.findByLabelOrderByIdDesc(eq(CorpusLabel.VIOLATION), any()))
                .thenReturn(violations);
        when(corpusRepository.findByLabelOrderByIdDesc(eq(CorpusLabel.COMPLIANT), any()))
                .thenReturn(compliant);
    }

    private JsonNode reply(boolean violation) {
        ObjectNode root = objectMapper.createObjectNode();
        root.putArray("choices").addObject().putObject("message").put("content",
                "{\"violation\":" + violation + ",\"violation_type\":\"INSULT\","
                        + "\"reason\":\"stub reason\",\"confidence\":0.9,\"target_sentence\":\"\"}");
        root.putObject("usage").put("prompt_tokens", 10).put("completion_tokens", 5);
        return root;
    }

    @Test
    void computesConfusionMatrixAndMetrics() {
        stubSamples(
                List.of(corpus(1L, CorpusLabel.VIOLATION, "v1 bad words"),
                        corpus(2L, CorpusLabel.VIOLATION, "v2 bad words")),
                List.of(corpus(3L, CorpusLabel.COMPLIANT, "c1 nice words"),
                        corpus(4L, CorpusLabel.COMPLIANT, "c2 nice words")));
        // v1 判违规(TP)、v2 判合规(FN)、c1 判违规(FP)、c2 判合规(TN)
        when(modelCaller.call(any(), anyString(), anyString(), anyLong())).thenAnswer(inv -> {
            String request = inv.getArgument(1);
            return reply(request.contains("v1 bad") || request.contains("c1 nice"));
        });

        ModelEvaluationService.EvaluationResult result = service.evaluate("stub", "stub-model", 4);

        assertEquals(4, result.total());
        assertEquals(4, result.evaluated());
        assertEquals(2, result.correct());
        assertEquals(0, result.failed());
        assertEquals(1, result.tp());
        assertEquals(1, result.fp());
        assertEquals(1, result.tn());
        assertEquals(1, result.fn());
        assertEquals(0.5, result.accuracy(), 1e-9);
        assertEquals(0.5, result.precision(), 1e-9);
        assertEquals(0.5, result.recall(), 1e-9);
        assertEquals("stub", result.providerId());
        assertEquals("stub-model", result.model());
        assertEquals(2, result.byLabel().size());
        ModelEvaluationService.LabelStat violations = result.byLabel().stream()
                .filter(l -> "VIOLATION".equals(l.label())).findFirst().orElseThrow();
        assertEquals(2, violations.total());
        assertEquals(1, violations.correct());
        ModelEvaluationService.LabelStat compliant = result.byLabel().stream()
                .filter(l -> "COMPLIANT".equals(l.label())).findFirst().orElseThrow();
        assertEquals(2, compliant.total());
        assertEquals(1, compliant.correct());
        assertEquals(4, result.results().size());
        assertTrue(result.results().stream().anyMatch(r -> r.expected().equals("VIOLATION")
                && r.predicted().equals("VIOLATION") && r.error() == null));

        verify(quotaService, times(4)).checkBeforeCall(any());
        verify(modelCaller, times(4)).call(any(), anyString(), anyString(),
                eq(ModelEvaluationService.CALL_TIMEOUT_SECONDS));
    }

    @Test
    void failedSamplesAreExcludedFromMetricDenominators() {
        stubSamples(List.of(corpus(1L, CorpusLabel.VIOLATION, "boom sample")),
                List.of(corpus(2L, CorpusLabel.COMPLIANT, "fine sample")));
        when(modelCaller.call(any(), anyString(), anyString(), anyLong())).thenAnswer(inv -> {
            String request = inv.getArgument(1);
            if (request.contains("boom sample")) {
                throw new BizException("HTTP 500: model down");
            }
            return reply(false);
        });

        ModelEvaluationService.EvaluationResult result = service.evaluate("stub", "stub-model", 2);

        assertEquals(2, result.total());
        assertEquals(1, result.evaluated(), "失败样本不参与指标分母");
        assertEquals(1, result.failed());
        assertEquals(1, result.correct());
        assertEquals(1.0, result.accuracy(), 1e-9);
        assertEquals(0.0, result.precision(), 1e-9, "tp+fp=0 时 precision 记 0");
        assertEquals(0.0, result.recall(), 1e-9, "tp+fn=0 时 recall 记 0");
        ModelEvaluationService.CaseResult failed = result.results().stream()
                .filter(r -> r.error() != null).findFirst().orElseThrow();
        assertEquals(1L, failed.corpusId());
        assertNull(failed.predicted());
        assertTrue(failed.error().contains("HTTP 500"));
    }

    @Test
    void capsSampleSizeAtTwenty() {
        List<CorpusEntry> violations = new ArrayList<>();
        List<CorpusEntry> compliant = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            violations.add(corpus(100L + i, CorpusLabel.VIOLATION, "v" + i));
            compliant.add(corpus(200L + i, CorpusLabel.COMPLIANT, "c" + i));
        }
        stubSamples(violations, compliant);
        when(modelCaller.call(any(), anyString(), anyString(), anyLong()))
                .thenAnswer(inv -> reply(false));

        ModelEvaluationService.EvaluationResult result = service.evaluate("stub", "stub-model", 100);

        assertEquals(ModelEvaluationService.MAX_SAMPLE_SIZE, result.total(), "样本数超过上限时按 20 处理");
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(corpusRepository, atLeastOnce())
                .findByLabelOrderByIdDesc(any(), pageableCaptor.capture());
        assertEquals(ModelEvaluationService.MAX_SAMPLE_SIZE, pageableCaptor.getValue().getPageSize());
    }

    @Test
    void quotaExceededAbortsEvaluationWithoutCallingModel() {
        stubSamples(List.of(corpus(1L, CorpusLabel.VIOLATION, "v")),
                List.of(corpus(2L, CorpusLabel.COMPLIANT, "c")));
        doThrow(new BizException("AI 每日 token 配额已用尽", HttpStatus.TOO_MANY_REQUESTS))
                .when(quotaService).checkBeforeCall(any());

        BizException error = assertThrows(BizException.class,
                () -> service.evaluate("stub", "stub-model", 2));

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, error.getStatus());
        verify(modelCaller, never()).call(any(), anyString(), anyString(), anyLong());
    }

    @Test
    void emptyCorpusIsRejected() {
        stubSamples(List.of(), List.of());

        BizException error = assertThrows(BizException.class,
                () -> service.evaluate("stub", "stub-model", 5));

        assertTrue(error.getMessage().contains("语料库"), error.getMessage());
        assertEquals(HttpStatus.BAD_REQUEST, error.getStatus());
        verify(quotaService, never()).checkBeforeCall(any());
    }

    @Test
    void unknownProviderIsRejectedBeforeCorpusLookup() {
        when(screenModelService.resolveModel("ghost", null))
                .thenThrow(new BizException("未知的模型供应商: ghost"));

        BizException error = assertThrows(BizException.class,
                () -> service.evaluate("ghost", null, 5));

        assertTrue(error.getMessage().contains("未知的模型供应商"));
        verify(corpusRepository, never()).findByLabelOrderByIdDesc(any(), any());
    }

    @Test
    void nonPositiveSampleSizeIsRejected() {
        BizException error = assertThrows(BizException.class,
                () -> service.evaluate("stub", "stub-model", 0));

        assertTrue(error.getMessage().contains("样本数"));
    }

    @Test
    void balancedSamplingBackfillsWhenOneLabelIsScarce() {
        stubSamples(List.of(corpus(1L, CorpusLabel.VIOLATION, "only violation")),
                List.of(corpus(2L, CorpusLabel.COMPLIANT, "c1"),
                        corpus(3L, CorpusLabel.COMPLIANT, "c2"),
                        corpus(4L, CorpusLabel.COMPLIANT, "c3")));
        when(modelCaller.call(any(), anyString(), anyString(), anyLong()))
                .thenAnswer(inv -> reply(false));

        ModelEvaluationService.EvaluationResult result = service.evaluate("stub", "stub-model", 4);

        assertEquals(4, result.total(), "一类样本不足时用另一类补齐");
        assertEquals(1, result.byLabel().stream()
                .filter(l -> "VIOLATION".equals(l.label())).findFirst().orElseThrow().total());
        assertEquals(3, result.byLabel().stream()
                .filter(l -> "COMPLIANT".equals(l.label())).findFirst().orElseThrow().total());
    }

    @Test
    void ratioRoundsToFourDecimalsAndHandlesZeroDenominator() {
        assertEquals(0.6667, ModelEvaluationService.ratio(2, 3), 1e-9);
        assertEquals(1.0, ModelEvaluationService.ratio(3, 3), 1e-9);
        assertEquals(0.0, ModelEvaluationService.ratio(0, 0), 1e-9);
    }
}
