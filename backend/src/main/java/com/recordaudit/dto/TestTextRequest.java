package com.recordaudit.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * DFA 检测工具请求
 */
public record TestTextRequest(@NotBlank(message = "检测文本不能为空") String text) {
}