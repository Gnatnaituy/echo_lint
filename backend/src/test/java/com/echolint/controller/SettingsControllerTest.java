package com.echolint.controller;

import com.echolint.domain.AuditAction;
import com.echolint.service.AuditService;
import com.echolint.service.ModelEvaluationService;
import com.echolint.service.NotificationService;
import com.echolint.service.ProviderAdminService;
import com.echolint.service.ProviderRegistry.ProviderSnapshot;
import com.echolint.service.ScreenModelService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 系统设置接口：通知（Batch 2b-2）+ AI 供应商 CRUD/测试（Batch 3a）的视图透传与审计。
 */
@ExtendWith(MockitoExtension.class)
class SettingsControllerTest {

    @Mock
    private ScreenModelService screenModelService;
    @Mock
    private ProviderAdminService providerAdminService;
    @Mock
    private ModelEvaluationService modelEvaluationService;
    @Mock
    private AuditService auditService;
    @Mock
    private NotificationService notificationService;

    private SettingsController controller;

    @BeforeEach
    void setUp() {
        controller = new SettingsController(screenModelService, providerAdminService, modelEvaluationService,
                auditService, notificationService);
    }

    private ProviderSnapshot snapshot(String key, boolean builtIn, String masked, boolean configured) {
        return new ProviderSnapshot(key, "Label " + key, "https://api.example.com", "/v1/chat/completions",
                "model-a", List.of("model-a", "model-b"), true, 2048,
                new BigDecimal("0.010000"), new BigDecimal("0.020000"), true, builtIn, masked, configured);
    }

    // ------------------------------------------------------------------ 通知

    @Test
    void notificationSettingsReturnServiceView() {
        NotificationService.SettingsView view = new NotificationService.SettingsView(
                true, true, 50, List.of("REVIEW_CONFIRMED_VIOLATION", "NOTIFY_TEST"));
        when(notificationService.settings()).thenReturn(view);

        NotificationService.SettingsView result = controller.notifications();

        assertSame(view, result);
        assertTrue(result.enabled());
        assertTrue(result.urlConfigured());
        assertEquals(50, result.backlogThreshold());
        assertEquals(List.of("REVIEW_CONFIRMED_VIOLATION", "NOTIFY_TEST"), result.events());
    }

    @Test
    void successfulTestNotificationIsAudited() {
        when(notificationService.test()).thenReturn(new NotificationService.TestResult(true, 23, "发送成功"));

        NotificationService.TestResult result = controller.testNotification();

        assertTrue(result.ok());
        assertEquals(23, result.latencyMs());
        assertEquals("发送成功", result.message());
        verify(auditService).record(eq(AuditAction.NOTIFY_TEST), eq("SETTINGS"), eq("notifications"),
                contains("测试通知发送成功"));
    }

    @Test
    void failedTestNotificationIsAuditedWithReason() {
        when(notificationService.test()).thenReturn(
                new NotificationService.TestResult(false, 0, "未配置可用的 webhook-url"));

        NotificationService.TestResult result = controller.testNotification();

        assertFalse(result.ok());
        verify(auditService).record(eq(AuditAction.NOTIFY_TEST), eq("SETTINGS"), eq("notifications"),
                contains("未配置可用的 webhook-url"));
    }

    @Test
    void testNotificationAuditNeverContainsSecretValues() {
        when(notificationService.test()).thenReturn(new NotificationService.TestResult(true, 5, "发送成功"));

        controller.testNotification();

        verify(auditService).record(eq(AuditAction.NOTIFY_TEST), eq("SETTINGS"), eq("notifications"),
                org.mockito.ArgumentMatchers.argThat(detail -> !detail.contains("secret")));
    }

    // ------------------------------------------------------------------ AI 供应商

    @Test
    void providerListPassesThroughMaskedView() {
        ProviderSnapshot masked = snapshot("custom-a", false, "****1234", true);
        when(providerAdminService.list()).thenReturn(List.of(masked));

        List<ProviderSnapshot> result = controller.providers();

        assertEquals(1, result.size());
        assertEquals("custom-a", result.get(0).key());
        assertEquals("****1234", result.get(0).apiKeyMasked());
        assertTrue(result.get(0).apiKeyConfigured());
        assertFalse(result.get(0).builtIn());
    }

    @Test
    void creatingProviderReturns201AndIsAudited() {
        ProviderSnapshot created = snapshot("custom-a", false, "****1234", true);
        when(providerAdminService.create(org.mockito.ArgumentMatchers.any())).thenReturn(created);

        ResponseEntity<ProviderSnapshot> response = controller.createProvider(
                new com.echolint.dto.ProviderRequest("custom-a", "Label custom-a", "https://api.example.com",
                        "/v1/chat/completions", "model-a", List.of("model-a"), true, 2048,
                        new BigDecimal("0.01"), new BigDecimal("0.02"), true, "sk-secret-1234"));

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertSame(created, response.getBody());
        verify(auditService).record(eq(AuditAction.PROVIDER_CREATE), eq("PROVIDER"), eq("custom-a"),
                contains("已配置"));
    }

    @Test
    void updatingProviderIsAudited() {
        ProviderSnapshot updated = snapshot("custom-a", false, "****1234", true);
        when(providerAdminService.update(eq("custom-a"), org.mockito.ArgumentMatchers.any())).thenReturn(updated);

        ProviderSnapshot result = controller.updateProvider("custom-a",
                new com.echolint.dto.ProviderRequest(null, "Label custom-a", "https://api.example.com",
                        "/v1/chat/completions", "model-a", List.of("model-a"), true, 2048,
                        BigDecimal.ZERO, BigDecimal.ZERO, true, ""));

        assertSame(updated, result);
        verify(auditService).record(eq(AuditAction.PROVIDER_UPDATE), eq("PROVIDER"), eq("custom-a"),
                contains("更新 AI 供应商"));
    }

    @Test
    void deletingProviderReturns204AndIsAudited() {
        ResponseEntity<Void> response = controller.deleteProvider("custom-a");

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        verify(providerAdminService).delete("custom-a");
        verify(auditService).record(eq(AuditAction.PROVIDER_DELETE), eq("PROVIDER"), eq("custom-a"),
                contains("删除 AI 供应商"));
    }

    @Test
    void providerTestIsAuditedWithoutSecrets() {
        when(screenModelService.test("custom-a", "model-a")).thenReturn(
                new ScreenModelService.TestResult(true, "custom-a", "Label custom-a", "model-a",
                        12, "{\"ok\":true}", "连通正常"));

        ScreenModelService.TestResult result = controller.testProvider("custom-a",
                new com.echolint.dto.ProviderTestRequest("model-a"));

        assertTrue(result.ok());
        verify(auditService).record(eq(AuditAction.PROVIDER_TEST), eq("PROVIDER"), eq("custom-a"),
                argThat(detail -> detail.contains("成功") && !detail.contains("sk-")));
    }

    @Test
    void failedProviderTestIsAuditedWithReason() {
        when(screenModelService.test("custom-a", null)).thenReturn(
                new ScreenModelService.TestResult(false, "custom-a", "Label custom-a", "model-a",
                        0, "", "调用异常: connect timed out"));

        ScreenModelService.TestResult result = controller.testProvider("custom-a", null);

        assertFalse(result.ok());
        verify(auditService).record(eq(AuditAction.PROVIDER_TEST), eq("PROVIDER"), eq("custom-a"),
                contains("connect timed out"));
    }

    // ------------------------------------------------------------------ 模型评测（Batch 3b-2）

    private ModelEvaluationService.EvaluationResult evaluationResult() {
        return new ModelEvaluationService.EvaluationResult(
                "custom-a", "model-a", 4, 3, 2, 1, 0.6667, 0.5, 1.0,
                1, 1, 1, 0, 123,
                List.of(new ModelEvaluationService.LabelStat("VIOLATION", 2, 1)),
                List.of(new ModelEvaluationService.CaseResult(1L, "VIOLATION", "VIOLATION", 0.9, "ok", null),
                        new ModelEvaluationService.CaseResult(2L, "COMPLIANT", null, 0, null, "HTTP 500")));
    }

    @Test
    void evaluateEndpointPassesThroughResultAndAudits() {
        ModelEvaluationService.EvaluationResult result = evaluationResult();
        when(modelEvaluationService.evaluate("custom-a", "model-a", 5)).thenReturn(result);

        ModelEvaluationService.EvaluationResult actual = controller.evaluateScreenModel(
                new com.echolint.dto.ModelEvaluationRequest("custom-a", "model-a", 5));

        assertSame(result, actual);
        verify(auditService).record(eq(AuditAction.MODEL_EVALUATE), eq("SETTINGS"), eq("screen-model"),
                argThat(detail -> detail.contains("custom-a") && detail.contains("model-a")
                        && detail.contains("accuracy=0.6667")));
    }

    @Test
    void evaluateAuditNeverContainsApiKeyMaterial() {
        when(modelEvaluationService.evaluate("custom-a", null, null)).thenReturn(evaluationResult());

        controller.evaluateScreenModel(new com.echolint.dto.ModelEvaluationRequest("custom-a", null, null));

        verify(auditService).record(eq(AuditAction.MODEL_EVALUATE), eq("SETTINGS"), eq("screen-model"),
                argThat(detail -> !detail.contains("sk-")));
    }
}
