package com.echolint.integration.tenant;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 租户解析适配器（Batch 5）。
 *
 * <p>实现类从请求中解析出当前租户 ID；本轮只提供上下文，不做数据隔离。
 * 解析失败应抛出 400 业务异常（{@code BizException}），由 {@link TenantFilter} 转成 JSON 400。</p>
 */
public interface TenantResolver {

    /** 解析当前请求的租户 ID（非空） */
    String resolve(HttpServletRequest request);
}
