package com.echolint.service;

import com.echolint.dfa.DfaHit;
import com.echolint.domain.CorpusLabel;
import com.echolint.domain.ViolationType;

import java.util.List;

/**
 * AI 语义复筛结果
 */
public record AiScreenResult(
        boolean violation,
        ViolationType violationType,
        String typeLabel,
        String reason,
        double confidence,
        String targetSentence) {

    public String typeCode() {
        return violationType == null ? ViolationType.NONE.name() : violationType.name();
    }

    public static AiScreenResult failed(String error) {
        return new AiScreenResult(false, ViolationType.NONE, ViolationType.NONE.getLabel(),
                "语义复筛调用失败: " + error, 0, "");
    }

    /** few-shot 参考案例 */
    public record CorpusExample(CorpusLabel label, String violationType, String transcript) {
    }
}