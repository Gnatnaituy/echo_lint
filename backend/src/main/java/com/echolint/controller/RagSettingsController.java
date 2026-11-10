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
 * <p>embedding 供应商/模型可在设置页切换（落库 app_settings，DB 优先、env 兜底），维度/路径/超时仍由 yml
 * 决定（不随模型热改）；换模型后需重建索引。重建索引/评测为异步或高成本操作，均写审计。
 */
@RestController
@RequestMapping("/api/settings/rag")
@RequiredArgsConstructor
public class RagSettingsController {

    private final RagAdminService adminService;
    private final RagEvaluationService evaluationService;
    private final AuditService auditService;

    /** embedding 变更段：字段省略=不修改；空字符串=回退 env 默认值 */
    public record EmbeddingInput(String provider, String model) {}

    public record UpdateRequest(
            Boolean enabled,
            Integer topK,
            Double minScore,
            Integer maxContextChars,
            Integer corpusExamples,
            EmbeddingInput embedding) {}

    public record ReindexRequest(String scope) {}

    public record EvaluateRequest(Integer sampleSize) {}

    @GetMapping
    public RagAdminService.RagView view() {
        return adminService.view();
    }

    @PutMapping
    public RagAdminService.UpdateView update(@RequestBody(required = false) UpdateRequest request) {
        RagAdminService.UpdateView view =
                adminService.update(
                        request == null ? null : request.enabled(),
                        request == null ? null : request.topK(),
                        request == null ? null : request.minScore(),
                        request == null ? null : request.maxContextChars(),
                        request == null ? null : request.corpusExamples(),
                        request == null || request.embedding() == null
                                ? null
                                : request.embedding().provider(),
                        request == null || request.embedding() == null
                                ? null
                                : request.embedding().model());
        auditService.record(
                AuditAction.RAG_SETTINGS_UPDATE,
                "RAG_SETTINGS",
                null,
                "更新 RAG 设置：enabled="
                        + view.enabled()
                        + "，topK="
                        + view.retrieval().topK()
                        + "，minScore="
                        + view.retrieval().minScore()
                        + "，maxContextChars="
                        + view.retrieval().maxContextChars()
                        + "，corpusExamples="
                        + view.retrieval().corpusExamples()
                        + "，embedding="
                        + view.embedding().providerId()
                        + " · "
                        + view.embedding().model()
                        + (view.embeddingChanged() ? "（已变更，需重建索引）" : ""));
        return view;
    }

    /** 真实调用一次 embedding 校验供应商/模型/维度是否可用 */
    @PostMapping("/test")
    public RagEmbeddingClient.TestResult test() {
        return adminService.test();
    }

    @PostMapping("/reindex")
    public RagAdminService.ReindexResult reindex(
            @RequestBody(required = false) ReindexRequest request) {
        String scope = request == null ? "all" : request.scope();
        RagAdminService.ReindexResult result = adminService.reindex(scope);
        auditService.record(
                AuditAction.RAG_REINDEX,
                "RAG_INDEX",
                null,
                "重建索引 scope=" + scope + "：" + result.message());
        return result;
    }

    /**
     * Phase 0：标注语料上的 with/without RAG 对比评测（同步接口，成本较高）。 样本数服务端封顶 20；单条调用超时 20s、单次评测总预算
     * 100s（超时中止剩余样本并标注原因）； 样本按生产 chunk-chars 截断（CaseResult.truncated=true）；每次调用写
     * ai_invocations（stage=EVAL）。
     */
    @PostMapping("/evaluate")
    public RagEvaluationService.EvaluationResult evaluate(
            @RequestBody(required = false) EvaluateRequest request) {
        Integer sampleSize = request == null ? null : request.sampleSize();
        RagEvaluationService.EvaluationResult result = evaluationService.evaluate(sampleSize);
        auditService.record(
                AuditAction.RAG_EVALUATE,
                "RAG_EVAL",
                null,
                "RAG 对比评测：样本 " + result.sampleSize() + "，模型 " + result.model());
        return result;
    }
}
