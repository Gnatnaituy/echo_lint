package com.echolint.domain;

import com.echolint.exception.BizException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 可见菜单目录：管理员可为用户勾选「在角色允许范围内收窄」的菜单，登录态返回生效菜单供前端隐藏入口。
 *
 * <p>枚举顺序即接口返回顺序。菜单绑定只影响前端可见性，<b>不改变</b> {@code SecurityConfig} 的 URL 权限矩阵（URL
 * 权限仍由角色独立校验，菜单绑定不能授予角色之外的权限）。
 *
 * <p>存储格式：逗号分隔的枚举名（如 {@code DASHBOARD,REPORTS}）；{@code null} 表示不限制 （= 角色允许的全部菜单）。
 */
public enum MenuKey {
    DASHBOARD("/", false),
    RECORDINGS("/recordings", false),
    REVIEW("/review", false),
    REPORTS("/reports", false),
    SCREEN("/screen", false),
    DICTIONARY("/dictionary", false),
    CORPUS("/corpus", false),
    KNOWLEDGE("/knowledge", false),
    SETTINGS("/settings", false),
    COSTS("/costs", true),
    TASKS("/tasks", true),
    AI_CHAT("/ai-chat", true),
    API_KEYS("/api-keys", true),
    USERS("/users", true),
    AUDIT("/audit", true);

    private final String path;
    private final boolean adminOnly;

    MenuKey(String path, boolean adminOnly) {
        this.path = path;
        this.adminOnly = adminOnly;
    }

    public String getPath() {
        return path;
    }

    /** 仅 ADMIN 可绑定 / 可见 */
    public boolean isAdminOnly() {
        return adminOnly;
    }

    /** 角色允许绑定的全部菜单（枚举顺序）。ADMIN 全部；REVIEWER / VIEWER 仅非 adminOnly。 */
    public static List<MenuKey> allowedFor(UserRole role) {
        List<MenuKey> allowed = new ArrayList<>(values().length);
        for (MenuKey key : values()) {
            if (role == UserRole.ADMIN || !key.adminOnly) {
                allowed.add(key);
            }
        }
        return List.copyOf(allowed);
    }

    /** 读路径宽松解析持久化值：跳过空项与未知/已下线 key，大小写不敏感。 */
    public static List<MenuKey> parseStored(String storedMenuKeys) {
        if (storedMenuKeys == null || storedMenuKeys.isBlank()) {
            return List.of();
        }
        Set<MenuKey> parsed = EnumSet.noneOf(MenuKey.class);
        for (String raw : storedMenuKeys.split(",")) {
            if (raw.isBlank()) {
                continue;
            }
            try {
                parsed.add(MenuKey.valueOf(raw.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
                // 读路径宽松：未知/已下线 key 直接跳过（写路径严格返回 400）
            }
        }
        List<MenuKey> ordered = new ArrayList<>(parsed.size());
        for (MenuKey key : values()) {
            if (parsed.contains(key)) {
                ordered.add(key);
            }
        }
        return List.copyOf(ordered);
    }

    /**
     * 生效菜单 = 存储 ∩ 角色允许；存储为空或交集为空时回退角色允许的全部菜单（fail-open 到角色权限）。
     *
     * @return 按枚举顺序排列的生效菜单
     */
    public static List<MenuKey> effective(UserRole role, String storedMenuKeys) {
        List<MenuKey> allowed = allowedFor(role);
        Set<MenuKey> stored = EnumSet.noneOf(MenuKey.class);
        stored.addAll(parseStored(storedMenuKeys));
        if (stored.isEmpty()) {
            return allowed;
        }
        List<MenuKey> effective = new ArrayList<>(stored.size());
        for (MenuKey key : allowed) {
            if (stored.contains(key)) {
                effective.add(key);
            }
        }
        return effective.isEmpty() ? allowed : List.copyOf(effective);
    }

    /** 生效菜单的枚举名列表（登录态 / 用户列表返回用） */
    public static List<String> effectiveNames(UserRole role, String storedMenuKeys) {
        return effective(role, storedMenuKeys).stream().map(MenuKey::name).toList();
    }

    /** 是否设置了收窄绑定（存储非 null 且非空） */
    public static boolean isRestricted(String storedMenuKeys) {
        return storedMenuKeys != null && !storedMenuKeys.isBlank();
    }

    /**
     * 写路径严格校验：至少 1 项、未知 key 400、角色不可用 key 400。
     *
     * @return 去重后按枚举顺序排列的选择
     */
    public static List<MenuKey> parseSelection(List<String> requested, UserRole role) {
        if (requested == null || requested.isEmpty()) {
            throw new BizException("至少需要选择 1 个菜单");
        }
        Set<MenuKey> selected = EnumSet.noneOf(MenuKey.class);
        for (String raw : requested) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            MenuKey key = parseKnown(raw.trim());
            if (role != UserRole.ADMIN && key.adminOnly) {
                throw new BizException("角色 " + role + " 不可绑定菜单: " + key.name());
            }
            selected.add(key);
        }
        if (selected.isEmpty()) {
            throw new BizException("至少需要选择 1 个菜单");
        }
        List<MenuKey> ordered = new ArrayList<>(selected.size());
        for (MenuKey key : values()) {
            if (selected.contains(key)) {
                ordered.add(key);
            }
        }
        return List.copyOf(ordered);
    }

    /**
     * 写路径落库值：去重后覆盖角色允许的全部菜单则折叠为 {@code null}（= 不限制），否则存逗号分隔子集。
     *
     * @param selection 已经过 {@link #parseSelection} 校验的选择
     */
    public static String toStored(List<MenuKey> selection, UserRole role) {
        List<MenuKey> allowed = allowedFor(role);
        if (selection == null || selection.isEmpty()) {
            throw new BizException("至少需要选择 1 个菜单");
        }
        List<MenuKey> ordered = new ArrayList<>(selection.size());
        for (MenuKey key : allowed) {
            if (selection.contains(key)) {
                ordered.add(key);
            }
        }
        if (ordered.size() == allowed.size()) {
            return null;
        }
        return joinNames(ordered);
    }

    /** 角色变更后按新角色过滤旧绑定：过滤为空或覆盖新角色全部菜单时折叠为 {@code null}（= 不限制）。 */
    public static String rebindForRole(String storedMenuKeys, UserRole role) {
        if (!isRestricted(storedMenuKeys)) {
            return null;
        }
        List<MenuKey> filtered = effective(role, storedMenuKeys);
        if (filtered.size() == allowedFor(role).size()) {
            return null;
        }
        return joinNames(filtered);
    }

    /** 审计用文案：{@code 菜单=全部} / {@code 菜单=N 项}（N 为生效项数）。 */
    public static String describe(UserRole role, String storedMenuKeys) {
        if (!isRestricted(storedMenuKeys)) {
            return "菜单=全部";
        }
        return "菜单=" + effective(role, storedMenuKeys).size() + " 项";
    }

    private static MenuKey parseKnown(String raw) {
        try {
            return MenuKey.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BizException("未知菜单: " + raw);
        }
    }

    private static String joinNames(List<MenuKey> keys) {
        StringBuilder joined = new StringBuilder();
        for (MenuKey key : keys) {
            if (joined.length() > 0) {
                joined.append(',');
            }
            joined.append(key.name());
        }
        return joined.toString();
    }
}
