package com.echolint.service;

import com.echolint.dfa.DfaHit;
import com.echolint.domain.CorpusLabel;
import com.echolint.domain.ViolationType;

import java.util.List;

/**
 * AI 语义复筛结果
 *
 * providerId/model 记录是哪个模型给出的结论，便于回溯与横向比较模型效果。
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
        String model) {

    public String typeCode() {
        return violationType == null ? ViolationType.NONE.name() : violationType.name();
    }

    /** 复筛调用失败时的降级结果（携带失败原因，转入人工复检兜底） */
    public static AiScreenResult failed(String error) {
        return new AiScreenResult(false, ViolationType.NONE, ViolationType.NONE.getLabel(),
                "语义复筛调用失败: " + error, 0, "", null, null, null);
    }

    /** few-shot 参考案例 */
    public record CorpusExample(CorpusLabel label, String violationType, String transcript) {
    }
}
