package com.echolint.dto;

import com.echolint.domain.UserRole;
import com.echolint.entity.AppUser;

import java.time.LocalDateTime;

/**
 * 用户管理列表项（绝不包含密码哈希）。
 */
public record UserView(
        Long id,
        String username,
        String displayName,
        UserRole role,
        boolean enabled,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static UserView from(AppUser user) {
        return new UserView(
                user.getId(),
                user.getUsername(),
                user.getDisplayName(),
                user.getRole(),
                user.isEnabled(),
                user.getCreatedAt(),
                user.getUpdatedAt());
    }
}
