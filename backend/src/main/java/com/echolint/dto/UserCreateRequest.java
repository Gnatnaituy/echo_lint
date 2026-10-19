package com.echolint.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 新建用户（ADMIN）。role 取 ADMIN / REVIEWER / VIEWER。
 */
public record UserCreateRequest(
        @NotBlank(message = "用户名不能为空") String username,
        @NotBlank(message = "密码不能为空") String password,
        String displayName,
        String role) {
}
