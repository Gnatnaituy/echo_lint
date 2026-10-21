package com.echolint.controller;

import com.echolint.domain.AuditAction;
import com.echolint.entity.KnowledgeEntry;
import com.echolint.service.AuditService;
import com.echolint.service.KnowledgeService;
import com.echolint.service.rag.RagDocumentService;
import com.echolint.service.rag.RagIndexService;
import com.echolint.service.rag.RagReference;
import com.echolint.service.rag.RagRetrievalService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库的 RAG 扩展接口（Batch 6）：文档导入、单条重建索引、检索预览。
 *
 * <p>与 {@link KnowledgeController} 共用 {@code /api/knowledge} 前缀，但方法/子路径不冲突：
 * POST 写操作沿用 SECURITY 规则（ADMIN + REVIEWER），检索预览同样仅写角色可见。</p>
 */
@RestController
@RequestMapping("/api/knowledge")
@RequiredArgsConstructor
public class KnowledgeRagController {

    private final KnowledgeService knowledgeService;
    private final RagDocumentService documentService;
    private final RagRetrievalService retrievalService;
    private final RagIndexService indexService;
    private final AuditService auditService;

    public record SearchRequest(String query, Integer topK) {
    }

    /** 导入 PDF/DOCX/TXT/MD：创建知识库条目并异步解析 + 向量化 */
    @PostMapping(value = "/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<KnowledgeEntry> importDocument(
            @RequestPart("file") MultipartFile file,
            @RequestParam(required = false) String title,
            @RequestParam(required = false) String category) {
        KnowledgeEntry entry = documentService.importDocument(file, title, category);
        auditService.record(AuditAction.RAG_DOCUMENT_IMPORT, "KNOWLEDGE_ENTRY", entry.getId(),
                "导入文档 " + entry.getSourceFilename() + " → 知识库条目 " + entry.getTitle());
        return ResponseEntity.status(HttpStatus.CREATED).body(entry);
    }

    /** 单条重建索引（异步） */
    @PostMapping("/{id}/reindex")
    public Map<String, Object> reindex(@PathVariable Long id) {
        KnowledgeEntry entry = knowledgeService.get(id);
        indexService.reindexKnowledgeEntry(id);
        auditService.record(AuditAction.RAG_REINDEX, "KNOWLEDGE_ENTRY", id,
                "重建知识库条目索引：" + entry.getTitle());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("status", "PENDING");
        return result;
    }

    /** 检索预览：用 query 走 pgvector，返回命中的知识片段与相似度 */
    @PostMapping("/search")
    public Map<String, Object> search(@RequestBody SearchRequest request) {
        String query = request == null ? null : request.query();
        int topK = request == null || request.topK() == null ? 4 : request.topK();
        List<RagReference> references = retrievalService.search(query, topK);
        List<Map<String, Object>> results = references.stream().map(ref -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("chunkId", ref.chunkId());
            row.put("entryId", ref.entryId());
            row.put("title", ref.title());
            row.put("category", ref.category());
            row.put("content", ref.content());
            row.put("score", ref.score());
            return row;
        }).toList();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("query", query);
        response.put("results", results);
        return response;
    }
}
