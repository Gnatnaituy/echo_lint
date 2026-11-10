package com.echolint.dto;

import com.echolint.domain.UserRole;
import com.echolint.security.AppUserDetails;
import java.util.List;

/**
 * 当前登录用户（不包含任何密码/令牌信息）。
 *
 * <p>{@code menuKeys} 为登录时刻的生效菜单快照（已按角色收窄，枚举顺序）；管理员调整菜单绑定后会 bump
 * 会话纪元，旧会话在下一次请求失效并重新登录，因此这里直接返回会话快照。
 */
public record AuthUserResponse(
        Long id, String username, String displayName, UserRole role, List<String> menuKeys) {

    public static AuthUserResponse from(AppUserDetails user) {
        return new AuthUserResponse(
                user.getId(),
                user.getUsername(),
                user.getDisplayName(),
                user.getRole(),
                user.getMenuKeys());
    }
}
