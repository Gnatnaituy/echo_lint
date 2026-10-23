package com.echolint.controller;

import com.echolint.domain.AuditAction;
import com.echolint.dto.KnowledgeRequest;
import com.echolint.entity.KnowledgeEntry;
import com.echolint.service.AuditService;
import com.echolint.service.KnowledgeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 知识库接口（Batch 4a）：分页上限、CRUD 透传与审计动作。
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeControllerTest {

    @Mock
    private KnowledgeService knowledgeService;
    @Mock
    private AuditService auditService;

    private KnowledgeController controller;

    @BeforeEach
    void setUp() {
        controller = new KnowledgeController(knowledgeService, auditService);
    }

    private KnowledgeEntry entry(long id, String title) {
        KnowledgeEntry entry = new KnowledgeEntry();
        entry.setId(id);
        entry.setTitle(title);
        entry.setCategory("合规");
        return entry;
    }

    private KnowledgeRequest request() {
        return new KnowledgeRequest("话术规范", "合规", "INSULT", "tag", "不要辱骂", true);
    }

    @Test
    void listClampsPaginationAndSortsByIdDesc() {
        when(knowledgeService.query(eq("辱骂"), eq("合规"), eq(true), eq("READY"), eq("DOCUMENT"),
                any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(entry(1L, "a"))));

        Page<KnowledgeEntry> page = controller.list("辱骂", "合规", true, "READY", "DOCUMENT", -1, 1000);

        assertEquals(1, page.getTotalElements());
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(knowledgeService).query(eq("辱骂"), eq("合规"), eq(true), eq("READY"), eq("DOCUMENT"),
                captor.capture());
        assertEquals(0, captor.getValue().getPageNumber());
        assertEquals(100, captor.getValue().getPageSize(), "size 硬上限 100");
        assertEquals("id: DESC", captor.getValue().getSort().toString());
    }

    @Test
    void createReturns201AndAudits() {
        KnowledgeEntry created = entry(9L, "话术规范");
        when(knowledgeService.create(any(KnowledgeRequest.class))).thenReturn(created);

        ResponseEntity<KnowledgeEntry> response = controller.create(request());

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertSame(created, response.getBody());
        verify(auditService).record(eq(AuditAction.KNOWLEDGE_CREATE), eq("KNOWLEDGE_ENTRY"), eq(9L),
                contains("话术规范"));
    }

    @Test
    void updateAudits() {
        KnowledgeEntry updated = entry(9L, "话术规范 v2");
        when(knowledgeService.update(eq(9L), any(KnowledgeRequest.class))).thenReturn(updated);

        KnowledgeEntry result = controller.update(9L, request());

        assertSame(updated, result);
        verify(auditService).record(eq(AuditAction.KNOWLEDGE_UPDATE), eq("KNOWLEDGE_ENTRY"), eq(9L),
                contains("v2"));
    }

    @Test
    void deleteReturns204AndAuditsDeletedTitle() {
        when(knowledgeService.delete(9L)).thenReturn(entry(9L, "待删除条目"));

        ResponseEntity<Void> response = controller.delete(9L);

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        verify(auditService).record(eq(AuditAction.KNOWLEDGE_DELETE), eq("KNOWLEDGE_ENTRY"), eq(9L),
                contains("待删除条目"));
    }

    @Test
    void detailPassesThrough() {
        KnowledgeEntry entry = entry(3L, "SOP");
        when(knowledgeService.get(3L)).thenReturn(entry);

        assertSame(entry, controller.detail(3L));
    }
}
