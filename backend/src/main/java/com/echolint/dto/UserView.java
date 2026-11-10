package com.echolint.dto;

import com.echolint.domain.MenuKey;
import com.echolint.domain.UserRole;
import com.echolint.entity.AppUser;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 用户管理列表项（绝不包含密码哈希）。
 *
 * <p>{@code menuKeys} 为生效菜单（已按角色收窄，枚举顺序）；{@code menuRestricted} 表示是否设置了 收窄绑定（存储非 null
 * 且非空），便于前端区分「全部」与「勾选子集」。
 */
public record UserView(
        Long id,
        String username,
        String displayName,
        UserRole role,
        boolean enabled,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        List<String> menuKeys,
        boolean menuRestricted) {

    public static UserView from(AppUser user) {
        return new UserView(
                user.getId(),
                user.getUsername(),
                user.getDisplayName(),
                user.getRole(),
                user.isEnabled(),
                user.getCreatedAt(),
                user.getUpdatedAt(),
                MenuKey.effectiveNames(user.getRole(), user.getMenuKeys()),
                MenuKey.isRestricted(user.getMenuKeys()));
    }
}
