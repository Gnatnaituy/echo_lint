package com.echolint.controller;

import com.echolint.domain.AuditAction;
import com.echolint.dto.KnowledgeRequest;
import com.echolint.entity.KnowledgeEntry;
import com.echolint.service.AuditService;
import com.echolint.service.KnowledgeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 知识库 / SOP（Batch 4a）：登录用户可读，ADMIN + REVIEWER 可写（见 SecurityConfig）。
 */
@RestController
@RequestMapping("/api/knowledge")
@RequiredArgsConstructor
public class KnowledgeController {

    /** 分页大小硬上限，防止一次拉全表 */
    private static final int MAX_PAGE_SIZE = 100;

    private final KnowledgeService knowledgeService;
    private final AuditService auditService;

    @GetMapping
    public Page<KnowledgeEntry> list(@RequestParam(required = false) String keyword,
                                     @RequestParam(required = false) String category,
                                     @RequestParam(required = false) Boolean enabled,
                                     @RequestParam(required = false) String indexStatus,
                                     @RequestParam(required = false) String sourceType,
                                     @RequestParam(defaultValue = "0") int page,
                                     @RequestParam(defaultValue = "10") int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        return knowledgeService.query(keyword, category, enabled, indexStatus, sourceType,
                PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "id")));
    }

    @GetMapping("/{id}")
    public KnowledgeEntry detail(@PathVariable Long id) {
        return knowledgeService.get(id);
    }

    @PostMapping
    public ResponseEntity<KnowledgeEntry> create(@Valid @RequestBody KnowledgeRequest request) {
        KnowledgeEntry created = knowledgeService.create(request);
        auditService.record(AuditAction.KNOWLEDGE_CREATE, "KNOWLEDGE_ENTRY", created.getId(),
                "新增知识库条目 " + created.getTitle());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/{id}")
    public KnowledgeEntry update(@PathVariable Long id, @Valid @RequestBody KnowledgeRequest request) {
        KnowledgeEntry updated = knowledgeService.update(id, request);
        auditService.record(AuditAction.KNOWLEDGE_UPDATE, "KNOWLEDGE_ENTRY", updated.getId(),
                "更新知识库条目 " + updated.getTitle());
        return updated;
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        KnowledgeEntry deleted = knowledgeService.delete(id);
        auditService.record(AuditAction.KNOWLEDGE_DELETE, "KNOWLEDGE_ENTRY", deleted.getId(),
                "删除知识库条目 " + deleted.getTitle());
        return ResponseEntity.noContent().build();
    }
}
