package com.echolint.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 模型评测请求（Batch 3b-2，仅 ADMIN）。
 *
 * @param providerId 评测使用的供应商 id（必须已存在）
 * @param model      模型名；留空回退到该供应商的 default-model
 * @param sampleSize 样本数（默认 10，上限 20）；VIOLATION / COMPLIANT 均衡抽样
 */
public record ModelEvaluationRequest(
        @NotBlank(message = "供应商不能为空") String providerId,
        String model,
        Integer sampleSize) {
}
