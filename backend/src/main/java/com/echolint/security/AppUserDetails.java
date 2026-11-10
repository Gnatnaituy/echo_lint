package com.echolint.security;

import com.echolint.domain.MenuKey;
import com.echolint.domain.UserRole;
import com.echolint.entity.AppUser;
import java.util.Collection;
import java.util.List;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * 登录态用户：Spring Security principal，随 JSESSIONID 存入会话。
 *
 * <p>角色映射为 {@code ROLE_ADMIN / ROLE_REVIEWER / ROLE_VIEWER}，供 URL 规则使用；菜单绑定 （{@code
 * menuKeys}）是登录时刻的生效菜单快照，仅用于前端隐藏入口，不参与鉴权。
 */
public class AppUserDetails implements UserDetails {

    private final Long id;
    private final String username;
    private final String password;
    private final String displayName;
    private final UserRole role;
    private final boolean enabled;
    private final List<String> menuKeys;

    public AppUserDetails(AppUser user) {
        this(
                user.getId(),
                user.getUsername(),
                user.getPasswordHash(),
                user.getDisplayName(),
                user.getRole(),
                user.isEnabled(),
                MenuKey.effectiveNames(user.getRole(), user.getMenuKeys()));
    }

    /** 兼容既有调用：未指定菜单绑定时等价于不限制（返回角色允许的全部菜单）。 */
    public AppUserDetails(
            Long id,
            String username,
            String password,
            String displayName,
            UserRole role,
            boolean enabled) {
        this(
                id,
                username,
                password,
                displayName,
                role,
                enabled,
                MenuKey.effectiveNames(role, null));
    }

    public AppUserDetails(
            Long id,
            String username,
            String password,
            String displayName,
            UserRole role,
            boolean enabled,
            List<String> menuKeys) {
        this.id = id;
        this.username = username;
        this.password = password;
        this.displayName = displayName;
        this.role = role == null ? UserRole.VIEWER : role;
        this.enabled = enabled;
        this.menuKeys = menuKeys == null ? List.of() : List.copyOf(menuKeys);
    }

    public Long getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public UserRole getRole() {
        return role;
    }

    /** 登录时刻的生效菜单（枚举名，枚举顺序） */
    public List<String> getMenuKeys() {
        return menuKeys;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override
    public String getPassword() {
        return password;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }
}
