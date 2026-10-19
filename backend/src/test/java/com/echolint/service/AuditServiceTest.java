package com.echolint.service;

import com.echolint.domain.AuditAction;
import com.echolint.domain.UserRole;
import com.echolint.entity.AuditLog;
import com.echolint.repository.AuditLogRepository;
import com.echolint.security.AppUserDetails;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 操作审计：写入失败绝不影响主流程、操作人取登录态、IP 取 X-Forwarded-For 首个值。
 */
@ExtendWith(MockitoExtension.class)
class AuditServiceTest {

    @Mock
    private AuditLogRepository auditLogRepository;

    private AuditService service;

    @BeforeEach
    void setUp() {
        service = new AuditService(auditLogRepository);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    private void loginAs(String username) {
        AppUserDetails details = new AppUserDetails(3L, username, "hash", username, UserRole.ADMIN, true);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities()));
    }

    private AuditLog captured() {
        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void auditFailureNeverBreaksBusinessFlow() {
        when(auditLogRepository.save(any(AuditLog.class)))
                .thenThrow(new RuntimeException("db down"));

        assertDoesNotThrow(() -> service.record(AuditAction.RECORDING_RETRY, "RECORDING", 7L, "重试"));
    }

    @Test
    void recordsCurrentActorActionAndTarget() {
        loginAs("alice");

        service.record(AuditAction.RECORDING_RETRY, "RECORDING", 7L, "重新处理");

        AuditLog entry = captured();
        assertEquals("alice", entry.getActor());
        assertEquals("RECORDING_RETRY", entry.getAction());
        assertEquals("RECORDING", entry.getTargetType());
        assertEquals("7", entry.getTargetId());
        assertEquals("重新处理", entry.getDetail());
    }

    @Test
    void fallsBackToSystemActorWithoutLogin() {
        service.record(AuditAction.SETTINGS_UPDATE, "SETTINGS", "screen-model", "切换模型");

        assertEquals("system", captured().getActor());
    }

    @Test
    void explicitActorOverridesLoginStateForLoginLogout() {
        service.recordAs("alice", AuditAction.LOGIN, "USER", 3L, "登录成功");

        AuditLog entry = captured();
        assertEquals("alice", entry.getActor());
        assertEquals("LOGIN", entry.getAction());
        assertEquals("3", entry.getTargetId());
    }

    @Test
    void ipUsesFirstForwardedForValue() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", "203.0.113.9, 10.0.0.1");
        request.setRemoteAddr("10.0.0.2");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        service.record(AuditAction.LOGIN, "USER", 3L, "登录成功");

        assertEquals("203.0.113.9", captured().getIp());
    }

    @Test
    void ipFallsBackToRemoteAddress() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("192.0.2.5");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        service.record(AuditAction.LOGIN, "USER", 3L, "登录成功");

        assertEquals("192.0.2.5", captured().getIp());
    }

    @Test
    void truncatesOversizedDetailToFitColumn() {
        loginAs("alice");

        service.record(AuditAction.RECORDING_UPLOAD, "RECORDING", 7L, "x".repeat(5000));

        assertEquals(2000, captured().getDetail().length());
    }
}
