package com.echolint.controller;

import com.echolint.dto.AuthUserResponse;
import com.echolint.dto.LoginRequest;
import com.echolint.dto.PasswordChangeRequest;
import com.echolint.service.AuthService;
import com.echolint.service.IntegrationStatusService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 认证：会话 cookie 登录/登出/当前用户/修改密码；Batch 5 增加公开的 SSO 配置状态。
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final IntegrationStatusService integrationStatusService;

    @PostMapping("/login")
    public AuthUserResponse login(@Valid @RequestBody LoginRequest request,
                                  HttpServletRequest httpRequest,
                                  HttpServletResponse httpResponse) {
        return authService.login(request, httpRequest, httpResponse);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest httpRequest) {
        authService.logout(httpRequest);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public AuthUserResponse me() {
        return authService.me();
    }

    @PostMapping("/password")
    public ResponseEntity<Void> changePassword(@Valid @RequestBody PasswordChangeRequest request) {
        authService.changePassword(request);
        return ResponseEntity.noContent().build();
    }

    /**
     * SSO 公开配置（permitAll，Batch 5）：登录页据此决定是否展示 SSO 入口。
     * 只返回 {enabled, provider, configured, loginUrl}，不含任何密钥；loginUrl 为空表示
     * 服务端已配置但客户端适配器尚未实现，前端不应跳转。
     */
    @GetMapping("/sso/config")
    public IntegrationStatusService.SsoStatus ssoConfig() {
        return integrationStatusService.ssoStatus();
    }
}
