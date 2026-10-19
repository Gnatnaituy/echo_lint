package com.echolint.service;

import com.echolint.dfa.DfaHit;
import com.echolint.domain.CorpusLabel;
import com.echolint.domain.ViolationType;

import java.util.List;

/**
 * AI 语义复筛结果
 *
 * <p>providerId/model 记录是哪个模型给出的结论，便于回溯与横向比较模型效果；
 * Batch 2b-2 起增加 {@code degraded}：AI 不可用、分块调用失败、或「判定违规但置信度低于
 * app.screen-models.min-confidence」都算降级 —— 降级结论一律转人工复检，不允许静默放行。</p>
 *
 * <p>Batch 3b-2 起增加 {@code screenVariant}：语义复筛 A/B 分流时记录本次实际走的组别
 * （"A"/"B"）；未开启分流时固定为 "A"，调用失败等无法确定组别时为 null。</p>
 */
public record AiScreenResult(
        boolean violation,
        ViolationType violationType,
        String typeLabel,
        String reason,
        double confidence,
        String targetSentence,
        String providerId,
        String providerLabel,
        String model,
        /** 是否降级（需要人工兜底）；true 时流水线一律转 NEEDS_REVIEW */
        boolean degraded,
        /** 降级原因（非降级时为 null） */
        String degradedReason,
        /** A/B 分组（"A"/"B"）；未分流或失败未知时为 null */
        String screenVariant) {

    /** 兼容既有调用方：默认非降级、A 组 */
    public AiScreenResult(boolean violation, ViolationType violationType, String typeLabel, String reason,
                          double confidence, String targetSentence, String providerId,
                          String providerLabel, String model) {
        this(violation, violationType, typeLabel, reason, confidence, targetSentence,
                providerId, providerLabel, model, false, null, "A");
    }

    /** 兼容既有调用方：未显式声明分组时按 A 组处理 */
    public AiScreenResult(boolean violation, ViolationType violationType, String typeLabel, String reason,
                          double confidence, String targetSentence, String providerId,
                          String providerLabel, String model, boolean degraded, String degradedReason) {
        this(violation, violationType, typeLabel, reason, confidence, targetSentence,
                providerId, providerLabel, model, degraded, degradedReason, "A");
    }

    public String typeCode() {
        return violationType == null ? ViolationType.NONE.name() : violationType.name();
    }

    /** 叠加降级标记（保留模型原始判定与 A/B 分组，便于人工复核时对照） */
    public AiScreenResult withDegraded(String why) {
        return new AiScreenResult(violation, violationType, typeLabel, reason, confidence,
                targetSentence, providerId, providerLabel, model, true, why, screenVariant);
    }

    /** 复筛调用失败时的降级结果（携带失败原因，转入人工复检兜底；组别未知） */
    public static AiScreenResult failed(String error) {
        return new AiScreenResult(false, ViolationType.NONE, ViolationType.NONE.getLabel(),
                "语义复筛调用失败: " + error, 0, "", null, null, null, true, error, null);
    }

    /** few-shot 参考案例 */
    public record CorpusExample(CorpusLabel label, String violationType, String transcript) {
    }
}
