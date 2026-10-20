package com.echolint.controller;

import com.echolint.domain.AuditAction;
import com.echolint.service.AuditService;
import com.echolint.service.rag.RagAdminService;
import com.echolint.service.rag.RagEmbeddingClient;
import com.echolint.service.rag.RagEvaluationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * RAG 检索增强设置（Batch 6，仅 ADMIN；URL 级权限见 SecurityConfig）。
 *
 * <p>embedding 供应商/模型/维度来自 yml（改动需重建索引），运行时只开放开关与检索参数；
 * 重建索引/评测为异步或高成本操作，均写审计。</p>
 */
@RestController
@RequestMapping("/api/settings/rag")
@RequiredArgsConstructor
public class RagSettingsController {

    private final RagAdminService adminService;
    private final RagEvaluationService evaluationService;
    private final AuditService auditService;

    public record UpdateRequest(Boolean enabled, Integer topK, Double minScore,
                                Integer maxContextChars, Integer corpusExamples) {
    }

    public record ReindexRequest(String scope) {
    }

    public record EvaluateRequest(Integer sampleSize) {
    }

    @GetMapping
    public RagAdminService.RagView view() {
        return adminService.view();
    }

    @PutMapping
    public RagAdminService.RagView update(@RequestBody(required = false) UpdateRequest request) {
        RagAdminService.RagView view = adminService.update(
                request == null ? null : request.enabled(),
                request == null ? null : request.topK(),
                request == null ? null : request.minScore(),
                request == null ? null : request.maxContextChars(),
                request == null ? null : request.corpusExamples());
        auditService.record(AuditAction.RAG_SETTINGS_UPDATE, "RAG_SETTINGS", null,
                "更新 RAG 设置：enabled=" + view.enabled() + "，topK=" + view.retrieval().topK()
                        + "，minScore=" + view.retrieval().minScore()
                        + "，maxContextChars=" + view.retrieval().maxContextChars()
                        + "，corpusExamples=" + view.retrieval().corpusExamples());
        return view;
    }

    /** 真实调用一次 embedding 校验供应商/模型/维度是否可用 */
    @PostMapping("/test")
    public RagEmbeddingClient.TestResult test() {
        return adminService.test();
    }

    @PostMapping("/reindex")
    public RagAdminService.ReindexResult reindex(@RequestBody(required = false) ReindexRequest request) {
        String scope = request == null ? "all" : request.scope();
        RagAdminService.ReindexResult result = adminService.reindex(scope);
        auditService.record(AuditAction.RAG_REINDEX, "RAG_INDEX", null,
                "重建索引 scope=" + scope + "：" + result.message());
        return result;
    }

    /** Phase 0：标注语料上的 with/without RAG 对比评测（20 条上限，成本较高） */
    @PostMapping("/evaluate")
    public RagEvaluationService.EvaluationResult evaluate(
            @RequestBody(required = false) EvaluateRequest request) {
        Integer sampleSize = request == null ? null : request.sampleSize();
        RagEvaluationService.EvaluationResult result = evaluationService.evaluate(sampleSize);
        auditService.record(AuditAction.RAG_EVALUATE, "RAG_EVAL", null,
                "RAG 对比评测：样本 " + result.sampleSize() + "，模型 " + result.model());
        return result;
    }
}
