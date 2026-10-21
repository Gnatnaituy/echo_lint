package com.echolint.dto;

import java.util.List;

/**
 * 「AI 对话记录」筛选下拉候选（从已有记录里聚合）。
 */
public record AiInvocationFacets(
        List<String> stages,
        List<ProviderOption> providers,
        List<String> models) {

    public record ProviderOption(String id, String label) {
    }
}
