package com.echolint.service;

import com.echolint.dto.AiInvocationDetail;
import com.echolint.dto.AiInvocationFacets;
import com.echolint.dto.AiInvocationFilter;
import com.echolint.dto.AiInvocationSummary;
import com.echolint.entity.AiInvocation;
import com.echolint.exception.BizException;
import com.echolint.repository.AiInvocationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AI 对话记录查询：列表摘要（不返回全文）、详情、筛选候选与 404。
 */
@ExtendWith(MockitoExtension.class)
class AiInvocationQueryServiceTest {

    @Mock
    private AiInvocationRepository repository;

    private AiInvocationQueryService service;

    @BeforeEach
    void setUp() {
        service = new AiInvocationQueryService(repository);
    }

    private AiInvocation entity(long id) {
        AiInvocation invocation = new AiInvocation();
        invocation.setId(id);
        invocation.setRecordingId(7L);
        invocation.setStage("SCREEN");
        invocation.setProviderId("deepseek");
        invocation.setProviderLabel("DeepSeek");
        invocation.setModel("deepseek-flash");
        invocation.setEndpoint("https://api.deepseek.com/chat/completions");
        invocation.setSuccess(true);
        invocation.setDegraded(false);
        invocation.setLatencyMs(812);
        invocation.setRequestChars(4096);
        invocation.setResponseChars(160);
        invocation.setInputTokens(1200);
        invocation.setOutputTokens(96);
        invocation.setCostMicros(1250L);
        invocation.setRequestJson("{\"messages\":[{\"role\":\"user\",\"content\":\"Huge prompt\"}]}");
        invocation.setResponseJson("{\"violation\":false}");
        invocation.setCreatedAt(LocalDateTime.of(2026, 10, 21, 10, 0));
        return invocation;
    }

    @Test
    void queryMapsSummariesWithoutPromptText() {
        when(repository.findAll(any(Specification.class), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(entity(12L)), PageRequest.of(0, 15), 1));

        Page<AiInvocationSummary> page = service.query(
                new AiInvocationFilter("SCREEN", "deepseek", null, true, 7L, null, null),
                PageRequest.of(0, 15));

        assertEquals(1, page.getTotalElements());
        AiInvocationSummary summary = page.getContent().get(0);
        assertEquals(12L, summary.id());
        assertEquals(7L, summary.recordingId());
        assertEquals("SCREEN", summary.stage());
        assertEquals(1200, summary.inputTokens());
        assertEquals(1250L, summary.costMicros());
    }

    @Test
    void detailReturnsFullRequestAndResponse() {
        when(repository.findById(12L)).thenReturn(Optional.of(entity(12L)));

        AiInvocationDetail detail = service.detail(12L);

        assertEquals(12L, detail.id());
        assertTrue(detail.requestJson().contains("Huge prompt"));
        assertEquals("{\"violation\":false}", detail.responseJson());
    }

    @Test
    void detailMissingThrows404() {
        when(repository.findById(99L)).thenReturn(Optional.empty());

        BizException error = assertThrows(BizException.class, () -> service.detail(99L));
        assertTrue(error.getMessage().contains("不存在"));
    }

    @Test
    void facetsMapProvidersAndModels() {
        when(repository.findDistinctStages()).thenReturn(List.of("MINING", "SCREEN"));
        when(repository.findDistinctProviders()).thenReturn(List.<Object[]>of(
                new Object[]{"aliyun", "阿里云百炼"},
                new Object[]{"deepseek", "DeepSeek"}));
        when(repository.findDistinctModels()).thenReturn(List.of("deepseek-flash", "qwen3.7-text-embedding"));

        AiInvocationFacets facets = service.facets();

        assertEquals(List.of("MINING", "SCREEN"), facets.stages());
        assertEquals(2, facets.providers().size());
        assertEquals("aliyun", facets.providers().get(0).id());
        assertEquals("阿里云百炼", facets.providers().get(0).label());
        assertEquals(2, facets.models().size());
    }

    @Test
    void queryWithoutFiltersStillQueriesRepository() {
        when(repository.findAll(any(Specification.class), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(entity(1L)), PageRequest.of(0, 15), 1));

        Page<AiInvocationSummary> page = service.query(null, PageRequest.of(0, 15));

        assertEquals(1, page.getContent().size());
        verify(repository).findAll(any(Specification.class), any(PageRequest.class));
    }
}
