package com.echolint.dto;

/**
 * 开放 API 上传录音响应（Batch 4a）：只回最小必要信息，不暴露内部路径/哈希。
 */
public record OpenUploadResponse(
        Long id,
        String status,
        String fileName) {
}
