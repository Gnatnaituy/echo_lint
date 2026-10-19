package com.echolint.controller;

import com.echolint.service.AuthService;
import com.echolint.service.IntegrationStatusService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * SSO 公开配置接口（Batch 5）：登录页可匿名读取，只返回 enabled/provider/configured/loginUrl。
 */
@ExtendWith(MockitoExtension.class)
class AuthControllerSsoConfigTest {

    @Mock
    private AuthService authService;
    @Mock
    private IntegrationStatusService integrationStatusService;

    private AuthController controller;

    @BeforeEach
    void setUp() {
        controller = new AuthController(authService, integrationStatusService);
    }

    @Test
    void ssoConfigIsDisabledByDefault() {
        when(integrationStatusService.ssoStatus())
                .thenReturn(new IntegrationStatusService.SsoStatus(false, "OIDC", false, ""));

        IntegrationStatusService.SsoStatus status = controller.ssoConfig();

        assertFalse(status.enabled());
        assertEquals("OIDC", status.provider());
        assertFalse(status.configured());
        assertEquals("", status.loginUrl());
    }

    @Test
    void ssoConfigExposesLoginUrlButNoSecrets() {
        when(integrationStatusService.ssoStatus()).thenReturn(
                new IntegrationStatusService.SsoStatus(true, "OIDC", true, "https://idp.example.com/authorize"));

        IntegrationStatusService.SsoStatus status = controller.ssoConfig();

        assertTrue(status.enabled());
        assertTrue(status.configured());
        assertEquals("https://idp.example.com/authorize", status.loginUrl());
        // record 只有四个字段：结构上保证 client-secret 不通过该接口泄露
        assertEquals(4, IntegrationStatusService.SsoStatus.class.getRecordComponents().length);
    }
}
