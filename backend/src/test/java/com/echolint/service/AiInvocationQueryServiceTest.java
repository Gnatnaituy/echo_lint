package com.echolint.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.echolint.dto.AiInvocationDetail;
import com.echolint.dto.AiInvocationFacets;
import com.echolint.dto.AiInvocationFilter;
import com.echolint.dto.AiInvocationSummary;
import com.echolint.entity.AiInvocation;
import com.echolint.exception.BizException;
import com.echolint.repository.AiInvocationRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

/** AI 对话记录查询：列表摘要（不返回全文）、详情、筛选候选与 404。 */
@ExtendWith(MockitoExtension.class)
class AiInvocationQueryServiceTest {

    @Mock private AiInvocationRepository repository;

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
        invocation.setRequestJson(
                "{\"messages\":[{\"role\":\"user\",\"content\":\"Huge prompt\"}]}");
        invocation.setResponseJson("{\"violation\":false}");
        invocation.setCreatedAt(LocalDateTime.of(2026, 10, 21, 10, 0));
        return invocation;
    }

    @Test
    void queryMapsSummariesWithoutPromptText() {
        AiInvocationRepository.SummaryProjection summary12 = projection(12L);
        when(repository.findSummaries(
                        any(), any(), any(), any(), any(), any(), any(), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(summary12), PageRequest.of(0, 15), 1));

        Page<AiInvocationSummary> page =
                service.query(
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

    private AiInvocationRepository.SummaryProjection projection(long id) {
        AiInvocationRepository.SummaryProjection p =
                mock(AiInvocationRepository.SummaryProjection.class);
        when(p.getId()).thenReturn(id);
        when(p.getRecordingId()).thenReturn(7L);
        when(p.getStage()).thenReturn("SCREEN");
        when(p.getProviderId()).thenReturn("deepseek");
        when(p.getProviderLabel()).thenReturn("DeepSeek");
        when(p.getModel()).thenReturn("deepseek-flash");
        when(p.getSuccess()).thenReturn(true);
        when(p.getDegraded()).thenReturn(false);
        when(p.getLatencyMs()).thenReturn(812);
        when(p.getRequestChars()).thenReturn(4096);
        when(p.getResponseChars()).thenReturn(160);
        when(p.getInputTokens()).thenReturn(1200);
        when(p.getOutputTokens()).thenReturn(96);
        when(p.getCostMicros()).thenReturn(1250L);
        when(p.getCreatedAt()).thenReturn(LocalDateTime.of(2026, 10, 21, 10, 0));
        return p;
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
        when(repository.findDistinctProviders())
                .thenReturn(
                        List.<Object[]>of(
                                new Object[] {"aliyun", "阿里云百炼"},
                                new Object[] {"deepseek", "DeepSeek"}));
        when(repository.findDistinctModels())
                .thenReturn(List.of("deepseek-flash", "qwen3.7-text-embedding"));

        AiInvocationFacets facets = service.facets();

        assertEquals(List.of("MINING", "SCREEN"), facets.stages());
        assertEquals(2, facets.providers().size());
        assertEquals("aliyun", facets.providers().get(0).id());
        assertEquals("阿里云百炼", facets.providers().get(0).label());
        assertEquals(2, facets.models().size());
    }

    @Test
    void queryWithoutFiltersStillQueriesRepository() {
        AiInvocationRepository.SummaryProjection summary1 = projection(1L);
        when(repository.findSummaries(
                        any(), any(), any(), any(), any(), any(), any(), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(summary1), PageRequest.of(0, 15), 1));

        Page<AiInvocationSummary> page = service.query(null, PageRequest.of(0, 15));

        assertEquals(1, page.getContent().size());
        verify(repository)
                .findSummaries(
                        any(), any(), any(), any(), any(), any(), any(), any(PageRequest.class));
    }
}
