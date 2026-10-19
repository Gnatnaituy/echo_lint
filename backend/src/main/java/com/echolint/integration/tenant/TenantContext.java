package com.echolint.integration.tenant;

import java.util.Optional;

/**
 * 当前请求的租户上下文（ThreadLocal）。
 *
 * <p>生命周期由 {@link TenantFilter} 管理：请求进入时设置、离开（含异常）时清理，
 * 避免线程池复用导致租户串号。异步线程不继承该上下文（本轮无数据隔离，不需要传播）。
 * 只允许通过本类读写，禁止业务代码直接操作 ThreadLocal。</p>
 */
public final class TenantContext {

    /** 单租户/默认租户 ID */
    public static final String DEFAULT_TENANT = "default";

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    /** 设置当前租户（过滤器内部使用） */
    public static void set(String tenantId) {
        CURRENT.set(tenantId);
    }

    /** 当前租户 ID；未设置时返回 empty */
    public static Optional<String> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    /** 清除当前线程的租户上下文（必须在 finally 中调用） */
    public static void clear() {
        CURRENT.remove();
    }
}
