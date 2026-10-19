package com.echolint.integration.tenant;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 单租户解析器（默认）：所有请求固定解析为 {@link TenantContext#DEFAULT_TENANT}。
 * 默认配置（app.tenant.enabled=false）下行为与 Batch 5 之前完全一致。
 */
public class SingleTenantResolver implements TenantResolver {

    @Override
    public String resolve(HttpServletRequest request) {
        return TenantContext.DEFAULT_TENANT;
    }
}
