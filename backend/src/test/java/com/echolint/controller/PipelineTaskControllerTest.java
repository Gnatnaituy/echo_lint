package com.echolint.controller;

import com.echolint.domain.AuditAction;
import com.echolint.domain.TaskStatus;
import com.echolint.dto.PipelineTaskView;
import com.echolint.exception.BizException;
import com.echolint.service.AuditService;
import com.echolint.service.PipelineTaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 任务队列 API（Batch 3b-1）：分页上限/排序、筛选参数解析、非法状态 400、重试审计与 409 透传。
 */
@ExtendWith(MockitoExtension.class)
class PipelineTaskControllerTest {

    @Mock
    private PipelineTaskService pipelineTaskService;
    @Mock
    private AuditService auditService;

    private PipelineTaskController controller;

    @BeforeEach
    void setUp() {
        controller = new PipelineTaskController(pipelineTaskService, auditService);
    }

    private PipelineTaskView view(TaskStatus status) {
        return new PipelineTaskView(7L, 5L, "call.mp3", status, 3, 3,
                LocalDateTime.now(), "boom", LocalDateTime.now(), LocalDateTime.now(), LocalDateTime.now());
    }

    @Test
    void listCapsPageSizeAtHundredAndSortsByIdDesc() {
        when(pipelineTaskService.query(isNull(), isNull(), any(Pageable.class)))
                .thenReturn(Page.<PipelineTaskView>empty());

        controller.list(null, null, 0, 10_000);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(pipelineTaskService).query(isNull(), isNull(), captor.capture());
        assertEquals(100, captor.getValue().getPageSize(), "size 硬上限 100");
        assertEquals(0, captor.getValue().getPageNumber());
        Sort.Order order = captor.getValue().getSort().getOrderFor("id");
        assertNotNull(order);
        assertEquals(Sort.Direction.DESC, order.getDirection());
    }

    @Test
    void listClampsNegativePageAndNonPositiveSize() {
        when(pipelineTaskService.query(isNull(), isNull(), any(Pageable.class)))
                .thenReturn(Page.<PipelineTaskView>empty());

        controller.list(null, null, -3, 0);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(pipelineTaskService).query(isNull(), isNull(), captor.capture());
        assertEquals(0, captor.getValue().getPageNumber());
        assertEquals(1, captor.getValue().getPageSize());
    }

    @Test
    void listPassesStatusAndRecordingFilters() {
        when(pipelineTaskService.query(eq(TaskStatus.DEAD), eq(5L), any(Pageable.class)))
                .thenReturn(Page.<PipelineTaskView>empty());

        controller.list("dead", 5L, 1, 20);

        verify(pipelineTaskService).query(eq(TaskStatus.DEAD), eq(5L), any(Pageable.class));
    }

    @Test
    void listRejectsUnknownStatus() {
        BizException e = assertThrows(BizException.class,
                () -> controller.list("SIDEWAYS", null, 0, 20));

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatus());
        assertTrue(e.getMessage().contains("任务状态不合法"));
    }

    @Test
    void retryResetsTaskAndWritesAudit() {
        when(pipelineTaskService.retry(7L)).thenReturn(view(TaskStatus.PENDING));

        PipelineTaskView result = controller.retry(7L);

        assertEquals(TaskStatus.PENDING, result.status());
        verify(auditService).record(eq(AuditAction.TASK_RETRY), eq("PIPELINE_TASK"), eq(7L),
                contains("录音 #5"));
    }

    @Test
    void retryPropagatesConflictForNonTerminalTask() {
        when(pipelineTaskService.retry(7L))
                .thenThrow(new BizException("仅 DEAD/FAILED 任务可重试", HttpStatus.CONFLICT));

        BizException e = assertThrows(BizException.class, () -> controller.retry(7L));

        assertEquals(HttpStatus.CONFLICT, e.getStatus());
        verify(auditService, org.mockito.Mockito.never())
                .record(eq(AuditAction.TASK_RETRY), any(), any(), any());
    }
}
