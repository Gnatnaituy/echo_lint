package com.echolint.controller;

import com.echolint.domain.AuditAction;
import com.echolint.service.AuditService;
import com.echolint.service.NotificationService;
import com.echolint.service.ScreenModelService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 系统设置的通知接口（ADMIN）：视图透传 + 测试发送后写 NOTIFY_TEST 审计。
 */
@ExtendWith(MockitoExtension.class)
class SettingsControllerTest {

    @Mock
    private ScreenModelService screenModelService;
    @Mock
    private AuditService auditService;
    @Mock
    private NotificationService notificationService;

    private SettingsController controller;

    @BeforeEach
    void setUp() {
        controller = new SettingsController(screenModelService, auditService, notificationService);
    }

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
}
