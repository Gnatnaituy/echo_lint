package com.echolint.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 创建 API Key 请求（Batch 4a，仅 ADMIN）
 */
public record ApiKeyCreateRequest(
        @NotBlank(message = "名称不能为空")
        @Size(max = 64, message = "名称最长 64 个字符")
        String name) {
}
