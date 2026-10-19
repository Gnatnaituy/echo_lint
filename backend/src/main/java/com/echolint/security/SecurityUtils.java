package com.echolint.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

/**
 * 从 SecurityContext 读取当前登录用户（会话认证）。
 */
public final class SecurityUtils {

    private SecurityUtils() {
    }

    public static Optional<AppUserDetails> currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return Optional.empty();
        }
        if (authentication.getPrincipal() instanceof AppUserDetails details) {
            return Optional.of(details);
        }
        return Optional.empty();
    }

    public static Optional<String> currentUsername() {
        return currentUser().map(AppUserDetails::getUsername);
    }
}
