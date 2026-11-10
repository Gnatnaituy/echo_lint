package com.echolint.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

/**
 * 新建用户（ADMIN）。role 取 ADMIN / REVIEWER / VIEWER。
 *
 * <p>{@code menuKeys} 为可见菜单绑定：省略 = 不限制（角色允许的全部菜单）；非空时严格校验 （未知 key / 角色越权 / 空数组均 400）。
 */
public record UserCreateRequest(
        @NotBlank(message = "用户名不能为空") String username,
        @NotBlank(message = "密码不能为空") String password,
        String displayName,
        String role,
        List<String> menuKeys) {}
