package com.echolint.integration.sso;

import java.util.Map;

/**
 * 默认 SSO 适配器（未启用）：登录仍走本地账号，状态接口返回 enabled=false。
 *
 * <p>真正接入 OIDC/LDAP 时，实现 {@link SsoProvider} 并用 {@code @Primary} 覆盖本 Bean，
 * 或删除本类并替换为实际实现（见 docs/integrations.md）。</p>
 */
public class DisabledSsoProvider implements SsoProvider {

    public static final String ID = "disabled";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public boolean enabled() {
        return false;
    }

    @Override
    public boolean configured() {
        return false;
    }

    @Override
    public String loginUrl() {
        return "";
    }

    @Override
    public Map<String, Object> metadata() {
        return Map.of("provider", id());
    }
}
