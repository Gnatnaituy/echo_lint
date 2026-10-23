package com.echolint.controller;

import com.echolint.dto.KnowledgeChunkPage;
import com.echolint.dto.KnowledgeChunkView;
import com.echolint.exception.BizException;
import com.echolint.service.KnowledgeIndexService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 知识库索引明细接口：分页夹取（page 下限 0 / size 1..100）、透传与 404 冒泡。
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeIndexControllerTest {

    @Mock
    private KnowledgeIndexService knowledgeIndexService;

    private KnowledgeIndexController controller;

    @BeforeEach
    void setUp() {
        controller = new KnowledgeIndexController(knowledgeIndexService);
    }

    private KnowledgeChunkPage page(int page, int size) {
        LocalDateTime now = LocalDateTime.of(2026, 10, 23, 12, 0);
        return new KnowledgeChunkPage(5L, "SOP", "READY", null, now, "hash",
                "qwen3.7-text-embedding", 1024, 7, page, size,
                List.of(new KnowledgeChunkView(11L, 0, "不要辱骂客户", "chunk-hash",
                        "qwen3.7-text-embedding", 1024, now, now)));
    }

    @Test
    void defaultPaginationPassesThroughServiceResult() {
        KnowledgeChunkPage expected = page(0, 10);
        when(knowledgeIndexService.detail(5L, 0, 10)).thenReturn(expected);

        assertSame(expected, controller.chunks(5L, 0, 10));
        verify(knowledgeIndexService).detail(5L, 0, 10);
    }

    @Test
    void negativePageClampsToZeroAndOversizedSizeClampsTo100() {
        when(knowledgeIndexService.detail(anyLong(), anyInt(), anyInt())).thenReturn(page(0, 100));

        controller.chunks(5L, -3, 1000);

        verify(knowledgeIndexService).detail(5L, 0, 100);
    }

    @Test
    void nonPositiveSizeClampsToOne() {
        when(knowledgeIndexService.detail(anyLong(), anyInt(), anyInt())).thenReturn(page(0, 1));

        controller.chunks(5L, 0, 0);
        controller.chunks(5L, 0, -10);

        verify(knowledgeIndexService, times(2)).detail(5L, 0, 1);
    }

    @Test
    void notFoundBubblesAsBizException() {
        when(knowledgeIndexService.detail(404L, 0, 10))
                .thenThrow(new BizException("知识库条目不存在: 404", HttpStatus.NOT_FOUND));

        BizException error = assertThrows(BizException.class, () -> controller.chunks(404L, 0, 10));

        assertEquals(HttpStatus.NOT_FOUND, error.getStatus());
    }
}
