package com.echolint.controller;

import com.echolint.dto.AiInvocationDetail;
import com.echolint.dto.AiInvocationFacets;
import com.echolint.dto.AiInvocationFilter;
import com.echolint.dto.AiInvocationSummary;
import com.echolint.service.AiInvocationQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * AI 对话记录（Batch 6）：管理员查看所有 AI 调用的提示词、原始回复与用量。
 *
 * <p>列表只返回摘要；详情返回完整请求/回复。ADMIN 权限由 SecurityConfig 独立校验。</p>
 */
@RestController
@RequestMapping("/api/ai-invocations")
@RequiredArgsConstructor
public class AiInvocationController {

    private static final int MAX_PAGE_SIZE = 100;

    private final AiInvocationQueryService queryService;

    @GetMapping
    public Page<AiInvocationSummary> list(
            @RequestParam(required = false) String stage,
            @RequestParam(required = false) String providerId,
            @RequestParam(required = false) String model,
            @RequestParam(required = false) Boolean success,
            @RequestParam(required = false) Long recordingId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        Pageable pageable = PageRequest.of(safePage, safeSize,
                Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id")));
        return queryService.query(new AiInvocationFilter(stage, providerId, model, success,
                recordingId, from, to), pageable);
    }

    @GetMapping("/facets")
    public AiInvocationFacets facets() {
        return queryService.facets();
    }

    @GetMapping("/{id}")
    public AiInvocationDetail detail(@PathVariable Long id) {
        return queryService.detail(id);
    }
}
