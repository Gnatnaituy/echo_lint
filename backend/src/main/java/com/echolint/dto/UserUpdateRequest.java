package com.echolint.dto;

import java.util.List;

/**
 * 更新用户（ADMIN）。字段为空表示不修改；password 非空时按新密码处理。
 *
 * <p>{@code menuKeys} 为 null / 省略表示不修改菜单绑定；显式传数组时按新角色严格校验， 全选等价于恢复「不限制」。
 */
public record UserUpdateRequest(
        String displayName, String role, Boolean enabled, String password, List<String> menuKeys) {}
