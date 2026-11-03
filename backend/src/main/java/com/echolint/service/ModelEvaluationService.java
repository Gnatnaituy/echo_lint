package com.echolint.service;

import com.echolint.config.ScreenModelProperties;
import com.echolint.domain.CorpusLabel;
import com.echolint.entity.CorpusEntry;
import com.echolint.exception.BizException;
import com.echolint.repository.CorpusEntryRepository;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 模型效果评测（Batch 3b-2，仅 ADMIN）。
 *
 * <p>从语料库取已标注样本（VIOLATION / COMPLIANT 均衡抽样，样本数默认 10、上限 20）， 用与线上语义复筛**完全一致的 system prompt
 * 与严格解析**逐条调用指定供应商/模型， 统计混淆矩阵与 accuracy / precision / recall。
 *
 * <p>约束：
 *
 * <ul>
 *   <li>样本数封顶 + 单条调用超时（{@value #CALL_TIMEOUT_SECONDS}s），避免管理员误操作放大成本；
 *   <li>few-shot 参考案例不注入、DFA 命中置空 —— 避免评测样本自身作为参考案例造成信息泄漏；
 *   <li>温度固定 0，提高可复现性；
 *   <li>每次调用前过 AI 配额（超限直接中止评测，不继续消耗）；
 *   <li>单条调用/解析失败计入 failed，不参与指标分母，但在结果中标注 error。
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModelEvaluationService {

    static final int DEFAULT_SAMPLE_SIZE = 10;
    static final int MAX_SAMPLE_SIZE = 20;

    /** 单条调用超时（P2-13 收紧：评测是同步接口，必须控制在前端 120s 预算内） */
    static final long CALL_TIMEOUT_SECONDS = 20;

    /** 单次评测总时长预算：超过后中止剩余样本并标注原因（P2-13） */
    static final long MAX_TOTAL_SECONDS = 100;

    private static final int MAX_TEXT = 300;

    private final CorpusEntryRepository corpusRepository;
    private final ScreenModelService screenModelService;
    private final ScreeningPromptSupport promptSupport;
    private final AiModelCaller modelCaller;
    private final AiQuotaService quotaService;
    private final AiInvocationService invocations;
    private final ScreenModelProperties screenModelProperties;

    /** 执行一次评测。相似度/耗时均为服务端实时统计。 */
    public EvaluationResult evaluate(
            String providerId, String requestedModel, Integer requestedSampleSize) {
        int sampleSize = effectiveSampleSize(requestedSampleSize);
        ScreenModelService.ActiveModel model =
                screenModelService.resolveModel(providerId, requestedModel);
        if (!StringUtils.hasText(model.model())) {
            throw new BizException("请指定模型名称（供应商 " + providerId + " 未配置 default-model）");
        }
        // 未配置密钥时直接失败（返回 500 由设置页提示补全密钥，避免跑 N 次空调用）
        screenModelService.requireUsable(model);

        List<CorpusEntry> samples = loadBalancedSamples(sampleSize);
        if (samples.isEmpty()) {
            throw new BizException("语料库暂无已标注样本，无法评测", HttpStatus.BAD_REQUEST);
        }

        int tp = 0;
        int fp = 0;
        int tn = 0;
        int fn = 0;
        int failed = 0;
        Map<CorpusLabel, int[]> labelStats = new EnumMap<>(CorpusLabel.class);
        for (CorpusLabel label : CorpusLabel.values()) {
            labelStats.put(label, new int[2]);
        }
        List<CaseResult> results = new ArrayList<>(samples.size());
        long start = System.currentTimeMillis();

        for (CorpusEntry sample : samples) {
            boolean expectedViolation = sample.getLabel() == CorpusLabel.VIOLATION;
            int[] stats = labelStats.get(sample.getLabel());
            stats[0]++;
            // P2-13：总时长预算，避免默认 10 条样本在慢供应商下拖过前端/nginx 超时
            if (System.currentTimeMillis() - start > MAX_TOTAL_SECONDS * 1000L) {
                failed++;
                results.add(
                        new CaseResult(
                                sample.getId(),
                                sample.getLabel().name(),
                                null,
                                0,
                                null,
                                false,
                                "评测总时长超过 " + MAX_TOTAL_SECONDS + "s，已中止剩余样本"));
                continue;
            }
            // P2-13：评测样本按生产 chunk-chars 截断，避免超长转写造成超大 prompt/超时（并标注 truncated）
            String transcript = sample.getTranscript() == null ? "" : sample.getTranscript();
            int chunkChars = screenModelProperties.effectiveChunkChars();
            boolean truncated = transcript.length() > chunkChars;
            if (truncated) {
                transcript = transcript.substring(0, chunkChars);
            }
            String requestJson = null;
            long callStart = System.currentTimeMillis();
            // 配额在 try 之外：超限属于全局停止条件，中止整次评测
            // 原子预留 + finally 释放（P2-9）：配额异常直接上抛，预留由 checkBeforeCall 内部释放
            try (AiQuotaService.Reservation ignored = quotaService.checkBeforeCall(model)) {
                try {
                    requestJson =
                            promptSupport.buildRequestBody(
                                    model, transcript, List.of(), List.of(), 1, 1, 0);
                    if (requestJson == null) {
                        throw new BizException("评测请求体序列化失败");
                    }
                    JsonNode response =
                            modelCaller.call(model, requestJson, "模型评测", CALL_TIMEOUT_SECONDS);
                    String content = ScreenModelService.contentOf(response);
                    // P2-10：评测调用写 ai_invocations（stage=EVAL），成本看板/审计可追溯；大 prompt 只存摘要
                    invocations.recordSuccess(
                            null,
                            AiInvocationService.STAGE_EVAL,
                            model,
                            AiInvocationService.summarizeRequest(requestJson),
                            ScreenModelService.truncate(content, 500),
                            System.currentTimeMillis() - callStart,
                            AiUsage.extract(response).pricedWith(model));
                    AiScreenResult verdict = promptSupport.parseResult(content, model, null, 1);
                    boolean predictedViolation = verdict.violation();
                    if (expectedViolation && predictedViolation) {
                        tp++;
                    } else if (expectedViolation) {
                        fn++;
                    } else if (predictedViolation) {
                        fp++;
                    } else {
                        tn++;
                    }
                    if (expectedViolation == predictedViolation) {
                        stats[1]++;
                    }
                    results.add(
                            new CaseResult(
                                    sample.getId(),
                                    sample.getLabel().name(),
                                    predictedViolation
                                            ? CorpusLabel.VIOLATION.name()
                                            : CorpusLabel.COMPLIANT.name(),
                                    verdict.confidence(),
                                    ScreenModelService.truncate(verdict.reason(), MAX_TEXT),
                                    truncated,
                                    null));
                } catch (RuntimeException e) {
                    failed++;
                    invocations.recordFailure(
                            null,
                            AiInvocationService.STAGE_EVAL,
                            model,
                            AiInvocationService.summarizeRequest(requestJson),
                            ScreenModelService.truncate(errorOf(e), MAX_TEXT),
                            System.currentTimeMillis() - callStart);
                    results.add(
                            new CaseResult(
                                    sample.getId(),
                                    sample.getLabel().name(),
                                    null,
                                    0,
                                    null,
                                    truncated,
                                    ScreenModelService.truncate(errorOf(e), MAX_TEXT)));
                    log.warn(
                            "模型评测样本 {} 调用失败（provider={}, model={}）：{}",
                            sample.getId(),
                            model.providerId(),
                            model.model(),
                            errorOf(e));
                }
            }
        }

        int evaluated = tp + fp + tn + fn;
        int correct = tp + tn;
        List<LabelStat> byLabel = new ArrayList<>();
        for (CorpusLabel label : CorpusLabel.values()) {
            int[] stats = labelStats.get(label);
            if (stats[0] > 0) {
                byLabel.add(new LabelStat(label.name(), stats[0], stats[1]));
            }
        }
        long latencyMs = System.currentTimeMillis() - start;
        log.info(
                "模型评测完成：{} · {}，样本 {}，有效 {}，失败 {}，accuracy={}，precision={}，recall={}，耗时 {}ms",
                model.providerId(),
                model.model(),
                samples.size(),
                evaluated,
                failed,
                ratio(correct, evaluated),
                ratio(tp, tp + fp),
                ratio(tp, tp + fn),
                latencyMs);

        return new EvaluationResult(
                model.providerId(),
                model.model(),
                samples.size(),
                evaluated,
                correct,
                failed,
                ratio(correct, evaluated),
                ratio(tp, tp + fp),
                ratio(tp, tp + fn),
                tp,
                fp,
                tn,
                fn,
                latencyMs,
                byLabel,
                results);
    }

    /** 均衡抽样：按目标样本数一半取 VIOLATION、一半取 COMPLIANT（最新的优先）； 某一类不足时用另一类补齐，语料总量不足则返回全部可用样本。 */
    public List<CorpusEntry> loadBalancedSamples(int sampleSize) {
        List<CorpusEntry> violations =
                corpusRepository.findByLabelOrderByIdDesc(
                        CorpusLabel.VIOLATION, PageRequest.of(0, sampleSize));
        List<CorpusEntry> compliant =
                corpusRepository.findByLabelOrderByIdDesc(
                        CorpusLabel.COMPLIANT, PageRequest.of(0, sampleSize));
        int violationTarget = sampleSize / 2;
        int compliantTarget = sampleSize - violationTarget;

        List<CorpusEntry> picked = new ArrayList<>(sampleSize);
        picked.addAll(violations.subList(0, Math.min(violationTarget, violations.size())));
        picked.addAll(compliant.subList(0, Math.min(compliantTarget, compliant.size())));
        // 某一类样本不足时用另一类补齐，保证尽量用满 sampleSize
        if (picked.size() < sampleSize) {
            for (CorpusEntry entry : violations) {
                if (picked.size() >= sampleSize) {
                    break;
                }
                if (!picked.contains(entry)) {
                    picked.add(entry);
                }
            }
        }
        if (picked.size() < sampleSize) {
            for (CorpusEntry entry : compliant) {
                if (picked.size() >= sampleSize) {
                    break;
                }
                if (!picked.contains(entry)) {
                    picked.add(entry);
                }
            }
        }
        return picked;
    }

    private static int effectiveSampleSize(Integer requested) {
        if (requested == null) {
            return DEFAULT_SAMPLE_SIZE;
        }
        int size = requested;
        if (size <= 0) {
            throw new BizException("样本数必须大于 0");
        }
        if (size > MAX_SAMPLE_SIZE) {
            log.warn("模型评测样本数 {} 超过上限，按 {} 处理", size, MAX_SAMPLE_SIZE);
            return MAX_SAMPLE_SIZE;
        }
        return size;
    }

    /** 指标保留 4 位小数，保证 JSON 输出稳定且便于前端展示 */
    static double ratio(int numerator, int denominator) {
        if (denominator <= 0) {
            return 0.0;
        }
        return Math.round((double) numerator / denominator * 10_000.0) / 10_000.0;
    }

    private static String errorOf(RuntimeException e) {
        String message = e.getMessage();
        return StringUtils.hasText(message) ? message : e.getClass().getSimpleName();
    }

    // ------------------------------------------------------------------ DTO

    /** 评测汇总。total = 抽样数量；evaluated = 有效调用数；failed = 失败数（不参与指标分母）。 */
    public record EvaluationResult(
            String providerId,
            String model,
            int total,
            int evaluated,
            int correct,
            int failed,
            double accuracy,
            double precision,
            double recall,
            int tp,
            int fp,
            int tn,
            int fn,
            long latencyMs,
            List<LabelStat> byLabel,
            List<CaseResult> results) {}

    /** 单个标注类别的样本数与判对数 */
    public record LabelStat(String label, int total, int correct) {}

    /** 单条评测结果：expected / predicted 取 VIOLATION / COMPLIANT；失败时 predicted 为 null 并带 error。 */
    public record CaseResult(
            Long corpusId,
            String expected,
            String predicted,
            double confidence,
            String reason,
            /** 样本是否按生产分块长度截断（P2-13） */
            boolean truncated,
            String error) {}
}
