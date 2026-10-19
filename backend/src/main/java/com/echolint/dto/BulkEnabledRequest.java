package com.echolint.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 词库批量启用/停用请求。
 */
public record BulkEnabledRequest(
        @NotNull(message = "ids 不能为空") List<Long> ids,
        @NotNull(message = "enabled 不能为空") Boolean enabled) {
}
