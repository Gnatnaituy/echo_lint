package com.echolint.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.echolint.domain.AuditAction;
import com.echolint.service.AuditService;
import com.echolint.service.rag.RagAdminService;
import com.echolint.service.rag.RagEmbeddingClient;
import com.echolint.service.rag.RagEvaluationService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** RAG 设置接口：视图/更新/测试/重建/评测 + 审计动作。 */
@ExtendWith(MockitoExtension.class)
class RagSettingsControllerTest {

    @Mock private RagAdminService adminService;
    @Mock private RagEvaluationService evaluationService;
    @Mock private AuditService auditService;

    private RagSettingsController controller;

    @BeforeEach
    void setUp() {
        controller = new RagSettingsController(adminService, evaluationService, auditService);
    }

    private RagAdminService.RagView view() {
        return new RagAdminService.RagView(
                true,
                "settings",
                new RagAdminService.EmbeddingView(
                        "openai",
                        "OpenAI",
                        "text-embedding-3-small",
                        "/v1/embeddings",
                        1536,
                        30,
                        true,
                        "DATABASE",
                        List.of("text-embedding-3-small", "text-embedding-3-large")),
                new RagAdminService.RetrievalView(4, 0.25, 2400, 5),
                new RagAdminService.IndexView(10, 8, 1, 1, 20, 18, null, true));
    }

    @Test
    void getReturnsView() {
        when(adminService.view()).thenReturn(view());

        RagAdminService.RagView result = controller.view();

        assertEquals(4, result.retrieval().topK());
        assertTrue(result.index().vectorAvailable());
    }

    @Test
    void updateDelegatesAndAudits() {
        when(adminService.update(true, 6, 0.3, 3000, 8, "deepseek", "text-embedding-v4"))
                .thenReturn(RagAdminService.UpdateView.of(view(), true));

        RagAdminService.UpdateView result =
                controller.update(
                        new RagSettingsController.UpdateRequest(
                                true,
                                6,
                                0.3,
                                3000,
                                8,
                                new RagSettingsController.EmbeddingInput(
                                        "deepseek", "text-embedding-v4")));

        assertEquals(4, result.retrieval().topK());
        assertTrue(result.embeddingChanged());
        verify(adminService).update(true, 6, 0.3, 3000, 8, "deepseek", "text-embedding-v4");
        verify(auditService)
                .record(eq(AuditAction.RAG_SETTINGS_UPDATE), eq("RAG_SETTINGS"), eq(null), any());
    }

    @Test
    void updateWithoutEmbeddingSegmentPassesNulls() {
        when(adminService.update(null, null, null, null, null, null, null))
                .thenReturn(RagAdminService.UpdateView.of(view(), false));

        RagAdminService.UpdateView result = controller.update(null);

        assertFalse(result.embeddingChanged());
        verify(adminService).update(null, null, null, null, null, null, null);
    }

    @Test
    void testDelegatesToAdminService() {
        RagEmbeddingClient.TestResult testResult =
                new RagEmbeddingClient.TestResult(
                        true, "openai", "OpenAI", "text-embedding-3-small", 1536, 10, "ok");
        when(adminService.test()).thenReturn(testResult);

        assertSame(testResult, controller.test());
    }

    @Test
    void reindexDefaultsToAllAndAudits() {
        when(adminService.reindex("all"))
                .thenReturn(new RagAdminService.ReindexResult(30, 0, "ok"));

        RagAdminService.ReindexResult result = controller.reindex(null);

        assertEquals(30, result.queued());
        verify(auditService).record(eq(AuditAction.RAG_REINDEX), eq("RAG_INDEX"), eq(null), any());
    }

    @Test
    void evaluateDelegatesAndAudits() {
        RagEvaluationService.Metrics metrics =
                new RagEvaluationService.Metrics(2, 2, 0, 1.0, 1.0, 1.0, 1, 0, 1, 0, 100);
        RagEvaluationService.EvaluationResult result =
                new RagEvaluationService.EvaluationResult(
                        "openai",
                        "gpt-4o-mini",
                        2,
                        metrics,
                        metrics,
                        new RagEvaluationService.Delta(0.0, 0.0, 0.0),
                        List.of());
        when(evaluationService.evaluate(10)).thenReturn(result);

        RagEvaluationService.EvaluationResult evaluated =
                controller.evaluate(new RagSettingsController.EvaluateRequest(10));

        assertEquals(2, evaluated.sampleSize());
        verify(auditService).record(eq(AuditAction.RAG_EVALUATE), eq("RAG_EVAL"), eq(null), any());
    }
}
