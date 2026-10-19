package com.echolint.integration.sso;

import java.util.Map;

/**
 * SSO / LDAP 身份源适配器（Batch 5）。
 *
 * <p>本轮不引入 OIDC/LDAP 客户端依赖，默认实现 {@link DisabledSsoProvider} 永远返回
 * 「未启用/未配置/无登录地址」。真正对接时实现本接口并注册为 Bean（替换/覆盖默认 Bean），
 * 即可让 {@code GET /api/auth/sso/config} 与集成状态接口反映真实状态。</p>
 *
 * <p>安全约定：实现类不得把 client-secret、绑定密码等敏感值放进 {@link #metadata()} 或日志。</p>
 */
public interface SsoProvider {

    /** 适配器标识（如 oidc / ldap / disabled） */
    String id();

    /** 是否启用（由配置 + 适配器就绪状态决定） */
    boolean enabled();

    /** 必要配置是否齐全（状态展示用，不代表协议流程已实现） */
    boolean configured();

    /** 登录页跳转地址；无真实适配器时返回空串 */
    String loginUrl();

    /** 附加元数据（如 provider 类型/issuer 主机），不得包含任何密钥 */
    default Map<String, Object> metadata() {
        return Map.of();
    }
}
