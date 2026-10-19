package com.echolint.security;

import com.echolint.domain.UserRole;
import com.echolint.entity.AppUser;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

/**
 * 登录态用户：Spring Security principal，随 JSESSIONID 存入会话。
 *
 * <p>角色映射为 {@code ROLE_ADMIN / ROLE_REVIEWER / ROLE_VIEWER}，供 URL 规则使用。</p>
 */
public class AppUserDetails implements UserDetails {

    private final Long id;
    private final String username;
    private final String password;
    private final String displayName;
    private final UserRole role;
    private final boolean enabled;

    public AppUserDetails(AppUser user) {
        this(user.getId(), user.getUsername(), user.getPasswordHash(),
                user.getDisplayName(), user.getRole(), user.isEnabled());
    }

    public AppUserDetails(Long id, String username, String password,
                          String displayName, UserRole role, boolean enabled) {
        this.id = id;
        this.username = username;
        this.password = password;
        this.displayName = displayName;
        this.role = role == null ? UserRole.VIEWER : role;
        this.enabled = enabled;
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
