package com.echolint.service;

import com.echolint.dto.KnowledgeRequest;
import com.echolint.entity.KnowledgeEntry;
import com.echolint.exception.BizException;
import com.echolint.repository.KnowledgeEntryRepository;
import com.echolint.service.rag.RagIndexService;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 知识库（Batch 4a）：过滤条件拼装（关键字/分类/启用）、CRUD 校验、404、createdBy 保留。
 */
@ExtendWith(MockitoExtension.class)
@SuppressWarnings({"unchecked", "rawtypes"})
class KnowledgeServiceTest {

    @Mock
    private KnowledgeEntryRepository repository;
    @Mock
    private RagIndexService ragIndexService;
    @Mock
    private Root<KnowledgeEntry> root;
    @Mock
    private CriteriaQuery<?> criteriaQuery;
    @Mock
    private CriteriaBuilder cb;
    @Mock
    private Path<Object> path;
    @Mock
    private Expression<String> lowered;

    private KnowledgeService service;

    @BeforeEach
    void setUp() {
        service = new KnowledgeService(repository, ragIndexService);
        lenient().when(root.get(anyString())).thenAnswer(inv -> path);
        lenient().when(cb.lower(any())).thenAnswer(inv -> lowered);
        lenient().when(cb.like(any(Expression.class), anyString())).thenAnswer(inv -> mock(Predicate.class));
        lenient().when(cb.equal(any(), any())).thenAnswer(inv -> mock(Predicate.class));
        lenient().when(cb.or(any(Predicate.class), any(Predicate.class), any(Predicate.class)))
                .thenAnswer(inv -> mock(Predicate.class));
        lenient().when(cb.and(any(Predicate[].class))).thenAnswer(inv -> mock(Predicate.class));
    }

    private Predicate evaluate(String keyword, String category, Boolean enabled) {
        return KnowledgeService.specification(keyword, category, enabled)
                .toPredicate(root, criteriaQuery, cb);
    }

    @Test
    void keywordFiltersTitleContentAndTags() {
        evaluate("  Insult ", null, null);

        verify(cb, times(3)).like(any(Expression.class), eq("%insult%"));
        verify(root).get("title");
        verify(root).get("content");
        verify(root).get("tags");
        verify(cb).or(any(Predicate.class), any(Predicate.class), any(Predicate.class));
    }

    @Test
    void categoryAndEnabledFiltersApplied() {
        evaluate(null, "  Complaints ", Boolean.FALSE);

        verify(cb).equal(lowered, "complaints");
        verify(root).get("category");
        verify(cb).equal(path, false);
        verify(root).get("enabled");
        verify(cb, never()).like(any(Expression.class), anyString());
    }

    @Test
    void noFiltersProducesEmptyAnd() {
        evaluate(null, "  ", null);

        verify(cb).and(any(Predicate[].class));
        verify(cb, never()).like(any(Expression.class), anyString());
        verify(cb, never()).equal(any(), any());
    }

    @Test
    void queryDelegatesToRepositoryWithPage() {
        Page<KnowledgeEntry> page = new PageImpl<>(List.of(new KnowledgeEntry()));
        PageRequest pageable = PageRequest.of(0, 10);
        when(repository.findAll(any(org.springframework.data.jpa.domain.Specification.class), eq(pageable)))
                .thenReturn(page);

        assertSame(page, service.query("x", "y", true, pageable));
    }

    @Test
    void createTrimsNormalizesAndDefaultsEnabled() {
        when(repository.save(any(KnowledgeEntry.class))).thenAnswer(inv -> {
            KnowledgeEntry e = inv.getArgument(0);
            e.setId(9L);
            return e;
        });

        KnowledgeEntry created = service.create(new KnowledgeRequest(
                "  话术规范 ", " 合规 ", " INSULT ", " tag1,tag2 ", " 不要辱骂客户 ", null));

        assertEquals(9L, created.getId());
        assertEquals("话术规范", created.getTitle());
        assertEquals("合规", created.getCategory());
        assertEquals("INSULT", created.getViolationType());
        assertEquals("tag1,tag2", created.getTags());
        assertEquals("不要辱骂客户", created.getContent());
        assertTrue(created.isEnabled(), "enabled 缺省 true");
        assertEquals("system", created.getCreatedBy());
    }

    @Test
    void createHonorsDisabledFlag() {
        when(repository.save(any(KnowledgeEntry.class))).thenAnswer(inv -> inv.getArgument(0));

        KnowledgeEntry created = service.create(new KnowledgeRequest(
                "SOP", null, null, null, null, false));

        assertFalse(created.isEnabled());
        assertNull(created.getCategory());
    }

    @Test
    void createRejectsBlankOrOversizedTitle() {
        assertTrue(assertThrows(BizException.class,
                () -> service.create(new KnowledgeRequest("   ", null, null, null, null, true)))
                .getMessage().contains("标题"));

        assertTrue(assertThrows(BizException.class,
                () -> service.create(new KnowledgeRequest("x".repeat(201), null, null, null, null, true)))
                .getMessage().contains("200"));
        verify(repository, never()).save(any());
    }

    @Test
    void updateReplacesFieldsButKeepsCreatedBy() {
        KnowledgeEntry existing = new KnowledgeEntry();
        existing.setId(3L);
        existing.setTitle("old");
        existing.setCreatedBy("owner");
        when(repository.findById(3L)).thenReturn(Optional.of(existing));
        when(repository.save(any(KnowledgeEntry.class))).thenAnswer(inv -> inv.getArgument(0));

        KnowledgeEntry updated = service.update(3L, new KnowledgeRequest(
                "new title", "cat", "THREAT", "a,b", "new content", false));

        assertEquals("new title", updated.getTitle());
        assertEquals("cat", updated.getCategory());
        assertEquals("THREAT", updated.getViolationType());
        assertEquals("new content", updated.getContent());
        assertFalse(updated.isEnabled());
        assertEquals("owner", updated.getCreatedBy(), "createdBy 不因编辑而变");
    }

    @Test
    void updateMissingEntryIs404() {
        when(repository.findById(404L)).thenReturn(Optional.empty());

        BizException error = assertThrows(BizException.class, () -> service.update(404L,
                new KnowledgeRequest("t", null, null, null, null, true)));

        assertEquals(HttpStatus.NOT_FOUND, error.getStatus());
    }

    @Test
    void deleteReturnsEntryForAuditAndRemovesIt() {
        KnowledgeEntry entry = new KnowledgeEntry();
        entry.setId(5L);
        entry.setTitle("to delete");
        when(repository.findById(5L)).thenReturn(Optional.of(entry));

        KnowledgeEntry deleted = service.delete(5L);

        assertSame(entry, deleted);
        verify(repository).delete(entry);
    }

    @Test
    void deleteMissingEntryIs404() {
        when(repository.findById(404L)).thenReturn(Optional.empty());

        BizException error = assertThrows(BizException.class, () -> service.delete(404L));

        assertEquals(HttpStatus.NOT_FOUND, error.getStatus());
        verify(repository, never()).delete(any(KnowledgeEntry.class));
    }
}
