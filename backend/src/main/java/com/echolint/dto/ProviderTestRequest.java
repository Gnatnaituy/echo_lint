package com.echolint.dto;

/**
 * 供应商连通性测试请求：model 可选，留空则用供应商默认模型。
 */
public record ProviderTestRequest(String model) {
}
