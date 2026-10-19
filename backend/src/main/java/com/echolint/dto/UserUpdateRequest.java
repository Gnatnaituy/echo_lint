package com.echolint.dto;

/**
 * 更新用户（ADMIN）。字段为空表示不修改；password 非空时按新密码处理。
 */
public record UserUpdateRequest(
        String displayName,
        String role,
        Boolean enabled,
        String password) {
}
