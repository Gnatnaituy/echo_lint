package com.echolint.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

/**
 * SSO / LDAP 身份源配置（Batch 5）。
 *
 * <p>本轮只提供适配器契约与只读状态，<b>不引入 OIDC/LDAP 客户端依赖</b>；
 * 真正对接需部署方提供 IdP 并实现 {@code SsoProvider}（见 docs/integrations.md）。
 * client-secret 只从环境变量/配置读取，绝不通过接口或日志返回。</p>
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.sso")
public class SsoProperties {

    /** 身份源类型 */
    public enum Provider {
        /** OpenID Connect（OAuth2 授权码 + PKCE） */
        OIDC,
        /** LDAP / Active Directory 目录服务 */
        LDAP
    }

    /** 总开关；false 时登录仍走本地账号 */
    private boolean enabled = false;

    /** 身份源类型：OIDC（默认）/ LDAP */
    private Provider provider = Provider.OIDC;

    /**
     * 签发方 / 目录地址。
     * OIDC：issuer（如 https://idp.example.com/realms/echolint）；
     * LDAP：ldap(s)://host:port（客户端实现负责 BaseDN 等细节）。
     */
    private String issuer = "";

    /** OIDC client_id / LDAP 绑定 DN */
    private String clientId = "";

    /** OIDC client_secret / LDAP 绑定密码（绝不外泄，仅配置注入） */
    private String clientSecret = "";

    /**
     * IdP 组/角色 → EchoLint 角色（ADMIN/REVIEWER/VIEWER）的映射，JSON 字符串。
     * 例：{@code {"echolint-admins":"ADMIN","echolint-reviewers":"REVIEWER"}}
     * 键名以 role-mapping-json 为准（推荐）；role-mapping 为兼容文档旧键的别名。
     */
    private String roleMappingJson = "";

    /** 兼容别键：app.sso.role-mapping（与 role-mapping-json 同时配置时以前者为准） */
    private String roleMapping = "";

    /** 生效的身份源类型（null 视为 OIDC） */
    public Provider effectiveProvider() {
        return provider == null ? Provider.OIDC : provider;
    }

    /**
     * 配置是否齐全（不判断 enabled —— 状态页需要区分「已配置但未启用」）。
     * OIDC/LDAP 统一要求 issuer + client-id + client-secret 三项非空；
     * LDAP 下 client-id/client-secret 分别对应绑定 DN/密码。
     */
    public boolean configured() {
        return StringUtils.hasText(issuer)
                && StringUtils.hasText(clientId)
                && StringUtils.hasText(clientSecret);
    }

    /** 生效的角色映射 JSON（推荐键优先，兼容旧键） */
    public String effectiveRoleMapping() {
        return StringUtils.hasText(roleMappingJson) ? roleMappingJson.trim() : roleMapping;
    }

    /** 是否配置了角色映射 */
    public boolean roleMappingConfigured() {
        return StringUtils.hasText(effectiveRoleMapping());
    }
}
