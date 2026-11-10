package com.echolint.dto;

import com.echolint.domain.MenuKey;
import java.util.Arrays;
import java.util.List;

/**
 * 用户管理里的菜单绑定可选项（ADMIN）：{@code key} 为持久化标识，{@code path} 供前端图标/跳转映射， {@code adminOnly} 标记仅 ADMIN 可绑定。
 */
public record MenuOptionView(String key, String path, boolean adminOnly) {

    public static MenuOptionView from(MenuKey menuKey) {
        return new MenuOptionView(menuKey.name(), menuKey.getPath(), menuKey.isAdminOnly());
    }

    /** 完整菜单目录（枚举顺序即展示顺序） */
    public static List<MenuOptionView> catalog() {
        return Arrays.stream(MenuKey.values()).map(MenuOptionView::from).toList();
    }
}
