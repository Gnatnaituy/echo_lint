package com.echolint.controller;

import com.echolint.domain.AuditAction;
import com.echolint.entity.KnowledgeEntry;
import com.echolint.service.AuditService;
import com.echolint.service.KnowledgeService;
import com.echolint.service.rag.RagDocumentService;
import com.echolint.service.rag.RagIndexService;
import com.echolint.service.rag.RagReference;
import com.echolint.service.rag.RagRetrievalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 知识库 RAG 扩展接口：文档导入、单条重建、检索预览。
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeRagControllerTest {

    @Mock private KnowledgeService knowledgeService;
    @Mock private RagDocumentService documentService;
    @Mock private RagRetrievalService retrievalService;
    @Mock private RagIndexService indexService;
    @Mock private AuditService auditService;

    private KnowledgeRagController controller;

    @BeforeEach
    void setUp() {
        controller = new KnowledgeRagController(knowledgeService, documentService, retrievalService,
                indexService, auditService);
    }

    private KnowledgeEntry entry() {
        KnowledgeEntry entry = new KnowledgeEntry();
        entry.setId(5L);
        entry.setTitle("SOP");
        entry.setSourceFilename("sop.pdf");
        return entry;
    }

    @Test
    void importDocumentReturns201AndAudits() {
        when(documentService.importDocument(any(), eq("标题"), eq("SOP")))
                .thenReturn(entry());
        MockMultipartFile file = new MockMultipartFile("file", "sop.pdf", "application/pdf",
                "%PDF-1.4".getBytes());

        ResponseEntity<KnowledgeEntry> response = controller.importDocument(file, "标题", "SOP");

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertEquals(5L, response.getBody().getId());
        verify(auditService).record(eq(AuditAction.RAG_DOCUMENT_IMPORT), eq("KNOWLEDGE_ENTRY"),
                eq(5L), any());
    }

    @Test
    void reindexExposesQueuedStatusAndAudits() {
        when(knowledgeService.get(5L)).thenReturn(entry());

        Map<String, Object> result = controller.reindex(5L);

        assertEquals(5L, result.get("id"));
        assertEquals("PENDING", result.get("status"));
        verify(indexService).reindexKnowledgeEntry(5L);
        verify(auditService).record(eq(AuditAction.RAG_REINDEX), eq("KNOWLEDGE_ENTRY"), eq(5L), any());
    }

    @Test
    void searchMapsReferences() {
        RagReference ref = new RagReference(11L, 5L, "SOP", "话术规范", "不要辱骂客户", 0.87);
        when(retrievalService.search("辱骂", 4)).thenReturn(List.of(ref));

        Map<String, Object> result = controller.search(new KnowledgeRagController.SearchRequest("辱骂", 4));

        assertEquals("辱骂", result.get("query"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) result.get("results");
        assertEquals(1, results.size());
        assertEquals(11L, results.get(0).get("chunkId"));
        assertEquals(5L, results.get(0).get("entryId"));
        assertEquals("SOP", results.get(0).get("title"));
        assertEquals(0.87, (double) results.get(0).get("score"), 1e-9);
    }

    @Test
    void searchDefaultsTopKWhenNull() {
        when(retrievalService.search("q", 4)).thenReturn(List.of());

        Map<String, Object> result = controller.search(new KnowledgeRagController.SearchRequest("q", null));

        assertTrue(((List<?>) result.get("results")).isEmpty());
        verify(retrievalService).search("q", 4);
    }
}
