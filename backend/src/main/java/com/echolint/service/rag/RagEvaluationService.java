package com.echolint.service.rag;

import com.echolint.domain.CorpusLabel;
import com.echolint.entity.CorpusEntry;
import com.echolint.exception.BizException;
import com.echolint.repository.CorpusEntryRepository;
import com.echolint.service.AiModelCaller;
import com.echolint.service.AiQuotaService;
import com.echolint.service.AiScreenResult;
import com.echolint.service.ModelEvaluationService;
import com.echolint.service.ScreenModelService;
import com.echolint.service.ScreeningPromptSupport;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Phase 0（RAG 价值验证）：对标注语料做 with/without RAG 的 A/B 评测。
 *
 * <p>对每条样本调用模型两次：基线（不注入知识库）与 RAG（注入知识库片段），
 * 统计 accuracy / precision / recall 与混淆矩阵，并给出逐条对比。
 * 这是判断「检索是否真的改善判定」的唯一硬指标；样本数上限 20，避免管理员误操作放大成本。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagEvaluationService {

    static final int MAX_SAMPLE_SIZE = 20;
    static final long CALL_TIMEOUT_SECONDS = 60;

    private final CorpusEntryRepository corpusRepository;
    private final ModelEvaluationService modelEvaluationService;
    private final ScreenModelService screenModelService;
    private final ScreeningPromptSupport promptSupport;
    private final AiModelCaller modelCaller;
    private final AiQuotaService quotaService;
    private final RagRetrievalService retrievalService;
    private final RagEmbeddingClient embeddingClient;

    public record Metrics(int evaluated, int correct, int failed, Double accuracy,
                          Double precision, Double recall, int tp, int fp, int tn, int fn,
                          long latencyMs) {
    }

    public record Delta(Double accuracy, Double precision, Double recall) {
    }

    public record CaseResult(long id, String label, String baselinePrediction, String ragPrediction,
                             Double baselineConfidence, Double ragConfidence,
                             List<String> references, String error) {
    }

    public record EvaluationResult(String providerId, String model, int sampleSize,
                                   Metrics baseline, Metrics rag, Delta delta,
                                   List<CaseResult> cases) {
    }

    public EvaluationResult evaluate(Integer requestedSampleSize) {
        int sampleSize = requestedSampleSize == null ? 10
                : Math.min(MAX_SAMPLE_SIZE, Math.max(1, requestedSampleSize));
        ScreenModelService.ActiveModel model = screenModelService.active();
        screenModelService.requireUsable(model);
        if (!embeddingClient.available()) {
            throw new BizException("embedding 供应商不可用（未配置密钥/已停用），无法做 RAG 对比评测",
                    HttpStatus.BAD_REQUEST);
        }
        List<CorpusEntry> samples = modelEvaluationService.loadBalancedSamples(sampleSize);
        if (samples.isEmpty()) {
            throw new BizException("语料库暂无已标注样本，无法评测", HttpStatus.BAD_REQUEST);
        }

        Counter baseline = new Counter();
        Counter rag = new Counter();
        List<CaseResult> cases = new ArrayList<>(samples.size());
        long start = System.currentTimeMillis();

        for (CorpusEntry sample : samples) {
            boolean expectedViolation = sample.getLabel() == CorpusLabel.VIOLATION;
            RagContext context = retrievalService.knowledgeContext(sample.getTranscript(), null, true);
            List<String> refTitles = context.references().stream().map(RagReference::title)
                    .filter(t -> t != null && !t.isBlank()).distinct().limit(5).toList();

            Attempt baseAttempt = attempt(model, sample.getTranscript(), List.of(), expectedViolation,
                    baseline, "基线");
            Attempt ragAttempt = attempt(model, sample.getTranscript(), context.references(),
                    expectedViolation, rag, "RAG");
            String error = baseAttempt.error() != null ? baseAttempt.error() : ragAttempt.error();
            cases.add(new CaseResult(sample.getId(), sample.getLabel().name(),
                    baseAttempt.prediction(), ragAttempt.prediction(),
                    baseAttempt.confidence(), ragAttempt.confidence(), refTitles, error));
            if (context.degraded()) {
                log.warn("RAG 评测样本 {} 检索降级：{}", sample.getId(), context.error());
            }
        }
        long latency = System.currentTimeMillis() - start;
        Metrics baselineMetrics = baseline.toMetrics(latency);
        Metrics ragMetrics = rag.toMetrics(latency);
        Delta delta = new Delta(diff(ragMetrics.accuracy(), baselineMetrics.accuracy()),
                diff(ragMetrics.precision(), baselineMetrics.precision()),
                diff(ragMetrics.recall(), baselineMetrics.recall()));
        log.info("RAG 评测完成：{} · {}，样本 {}，baseline acc={} / rag acc={}，耗时 {}ms",
                model.providerId(), model.model(), samples.size(),
                baselineMetrics.accuracy(), ragMetrics.accuracy(), latency);
        return new EvaluationResult(model.providerId(), model.model(), samples.size(),
                baselineMetrics, ragMetrics, delta, cases);
    }

    private Attempt attempt(ScreenModelService.ActiveModel model, String transcript,
                            List<RagReference> references, boolean expectedViolation,
                            Counter counter, String action) {
        try {
            quotaService.checkBeforeCall(model);
            String requestJson = promptSupport.buildRequestBody(model, transcript, List.of(), List.of(),
                    references, 1, 1, 0.0);
            if (requestJson == null) {
                throw new BizException("评测请求体序列化失败");
            }
            JsonNode response = modelCaller.call(model, requestJson, action + " RAG 评测",
                    CALL_TIMEOUT_SECONDS);
            String content = ScreenModelService.contentOf(response);
            AiScreenResult verdict = promptSupport.parseResult(content, model, null, 1);
            counter.add(expectedViolation, verdict.violation());
            return new Attempt(verdict.violation() ? "VIOLATION" : "COMPLIANT",
                    verdict.confidence(), null);
        } catch (RuntimeException e) {
            counter.failed++;
            return new Attempt(null, null, e.getMessage());
        }
    }

    private static Double diff(Double value, Double base) {
        return value == null || base == null ? null : value - base;
    }

    private static Double ratio(int numerator, int denominator) {
        return denominator == 0 ? null : (double) numerator / denominator;
    }

    private record Attempt(String prediction, Double confidence, String error) {
    }

    private static final class Counter {
        private int tp;
        private int fp;
        private int tn;
        private int fn;
        private int failed;

        void add(boolean expectedViolation, boolean predictedViolation) {
            if (expectedViolation && predictedViolation) {
                tp++;
            } else if (expectedViolation) {
                fn++;
            } else if (predictedViolation) {
                fp++;
            } else {
                tn++;
            }
        }

        Metrics toMetrics(long latencyMs) {
            int evaluated = tp + fp + tn + fn;
            return new Metrics(evaluated, tp + tn, failed,
                    ratio(tp + tn, evaluated), ratio(tp, tp + fp), ratio(tp, tp + fn),
                    tp, fp, tn, fn, latencyMs);
        }
    }
}
