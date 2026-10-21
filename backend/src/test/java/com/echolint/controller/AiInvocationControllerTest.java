package com.echolint.controller;

import com.echolint.dto.AiInvocationDetail;
import com.echolint.dto.AiInvocationFacets;
import com.echolint.dto.AiInvocationFilter;
import com.echolint.dto.AiInvocationSummary;
import com.echolint.service.AiInvocationQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * AI 对话记录接口：分页钳制、筛选参数透传、facets 与详情。
 */
@ExtendWith(MockitoExtension.class)
class AiInvocationControllerTest {

    @Mock
    private AiInvocationQueryService service;

    private AiInvocationController controller;

    @BeforeEach
    void setUp() {
        controller = new AiInvocationController(service);
    }

    @Test
    void listClampsPagingAndPassesFilters() {
        when(service.query(any(AiInvocationFilter.class), any(Pageable.class))).thenReturn(Page.empty());

        Page<AiInvocationSummary> result = controller.list("screen", "ALIYUN", "qwen",
                true, 7L, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 21), -3, 500);

        assertNotNull(result);
        ArgumentCaptor<Pageable> pageCaptor = ArgumentCaptor.forClass(Pageable.class);
        ArgumentCaptor<AiInvocationFilter> filterCaptor = ArgumentCaptor.forClass(AiInvocationFilter.class);
        verify(service).query(filterCaptor.capture(), pageCaptor.capture());

        Pageable pageable = pageCaptor.getValue();
        assertEquals(0, pageable.getPageNumber());
        assertEquals(100, pageable.getPageSize(), "size 硬上限 100");
        Sort.Order created = pageable.getSort().getOrderFor("createdAt");
        Sort.Order id = pageable.getSort().getOrderFor("id");
        assertEquals(Sort.Direction.DESC, created.getDirection());
        assertEquals(Sort.Direction.DESC, id.getDirection());

        AiInvocationFilter filter = filterCaptor.getValue();
        assertEquals("screen", filter.stage());
        assertEquals("ALIYUN", filter.providerId());
        assertEquals("qwen", filter.model());
        assertEquals(true, filter.success());
        assertEquals(7L, filter.recordingId());
        assertEquals(LocalDate.of(2026, 10, 1), filter.from());
        assertEquals(LocalDate.of(2026, 10, 21), filter.to());
    }

    @Test
    void listDefaultsPageAndSize() {
        when(service.query(any(AiInvocationFilter.class), any(Pageable.class))).thenReturn(Page.empty());

        controller.list(null, null, null, null, null, null, null, 0, 20);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(service).query(any(AiInvocationFilter.class), captor.capture());
        assertEquals(20, captor.getValue().getPageSize());
    }

    @Test
    void facetsDelegates() {
        AiInvocationFacets facets = new AiInvocationFacets(List.of("SCREEN"),
                List.of(new AiInvocationFacets.ProviderOption("deepseek", "DeepSeek")),
                List.of("deepseek-flash"));
        when(service.facets()).thenReturn(facets);

        assertSame(facets, controller.facets());
    }

    @Test
    void detailDelegates() {
        AiInvocationDetail detail = new AiInvocationDetail(1L, 2L, "SCREEN", "deepseek", "DeepSeek",
                "deepseek-flash", "endpoint", true, false, 100, 10, 10, 1, 1, 0L,
                null, null, "{}", "{}");
        when(service.detail(1L)).thenReturn(detail);

        assertSame(detail, controller.detail(1L));
        verify(service).detail(1L);
    }
}
