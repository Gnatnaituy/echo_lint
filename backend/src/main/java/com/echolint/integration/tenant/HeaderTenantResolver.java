package com.echolint.integration.tenant;

import com.echolint.exception.BizException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.util.StringUtils;

import java.util.regex.Pattern;

/**
 * 请求头租户解析器（app.tenant.enabled=true + mode=HEADER）。
 *
 * <p>租户 ID 规则：去掉首尾空白后长度 1~64，且只允许字母/数字/下划线/中划线，
 * 首字符必须是字母或数字。缺失或不符合规则统一抛 400，避免把任意（可能带攻击载荷的）
 * 请求头值写进上下文/日志。</p>
 */
public class HeaderTenantResolver implements TenantResolver {

    /** 与 {@link TenantContext} 的约定一致：1~64 位，首字符字母或数字 */
    static final Pattern TENANT_ID_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,63}");

    private final String headerName;

    public HeaderTenantResolver(String headerName) {
        this.headerName = StringUtils.hasText(headerName) ? headerName.trim() : "X-Tenant-Id";
    }

    public String headerName() {
        return headerName;
    }

    @Override
    public String resolve(HttpServletRequest request) {
        String raw = request.getHeader(headerName);
        if (!StringUtils.hasText(raw)) {
            throw new BizException("缺少租户标识请求头: " + headerName);
        }
        String tenantId = raw.trim();
        if (!TENANT_ID_PATTERN.matcher(tenantId).matches()) {
            // 不回显原始值，避免日志/响应注入；只提示规则
            throw new BizException("租户标识不合法（允许 1~64 位字母、数字、下划线或中划线）");
        }
        return tenantId;
    }
}
