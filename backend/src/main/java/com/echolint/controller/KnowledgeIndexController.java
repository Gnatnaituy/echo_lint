package com.echolint.controller;

import com.echolint.dto.KnowledgeChunkPage;
import com.echolint.service.KnowledgeIndexService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 知识库索引明细接口（仅 ADMIN，见 SecurityConfig）。
 *
 * <p>与 {@link KnowledgeController} / {@link KnowledgeRagController} 共用 {@code /api/knowledge}
 * 前缀，子路径 {@code /{id}/chunks} 不冲突；本接口只读，不写审计。</p>
 */
@RestController
@RequestMapping("/api/knowledge")
@RequiredArgsConstructor
public class KnowledgeIndexController {

    /** 分页大小硬上限，防止一次拉取过多分块正文 */
    private static final int MAX_PAGE_SIZE = 100;

    private final KnowledgeIndexService knowledgeIndexService;

    /** 分块明细：page 下限 0，size 夹在 1..100 */
    @GetMapping("/{id}/chunks")
    public KnowledgeChunkPage chunks(@PathVariable Long id,
                                     @RequestParam(defaultValue = "0") int page,
                                     @RequestParam(defaultValue = "10") int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        return knowledgeIndexService.detail(id, safePage, safeSize);
    }
}
