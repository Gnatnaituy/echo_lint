package com.echolint.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 管理员指派复核人。
 */
public record AssignRecordingRequest(@NotBlank(message = "用户名不能为空") String username) {
}
