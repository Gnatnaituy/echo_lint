package com.echolint.dto;

import com.echolint.domain.UserRole;
import com.echolint.security.AppUserDetails;

/**
 * 当前登录用户（不包含任何密码/令牌信息）。
 */
public record AuthUserResponse(
        Long id,
        String username,
        String displayName,
        UserRole role) {

    public static AuthUserResponse from(AppUserDetails user) {
        return new AuthUserResponse(user.getId(), user.getUsername(), user.getDisplayName(), user.getRole());
    }
}
