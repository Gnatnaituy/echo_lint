package com.echolint.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.echolint.config.AppProperties;
import com.echolint.domain.AuditAction;
import com.echolint.repository.AiInvocationRepository;
import com.echolint.repository.AuditLogRepository;
import com.echolint.repository.PipelineLogRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Pageable;

/** 数据保留清理（P2-30）：开关关闭不执行、按批删除（≤500/批）、审计留痕、单表失败不影响其它表。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DataRetentionServiceTest {

    @Mock private AiInvocationRepository aiInvocations;
    @Mock private AuditLogRepository auditLogs;
    @Mock private PipelineLogRepository pipelineLogs;
    @Mock private AuditService auditService;

    private AppProperties appProperties;
    private DataRetentionService service;

    @BeforeEach
    void setUp() {
        appProperties = new AppProperties();
        appProperties.getRetention().setEnabled(true);
        service =
                new DataRetentionService(
                        appProperties, aiInvocations, auditLogs, pipelineLogs, auditService);
    }

    @Test
    void disabledRetentionDoesNothing() {
        appProperties.getRetention().setEnabled(false);

        assertEquals(0, service.purgeExpired());
        verifyNoInteractions(aiInvocations, auditLogs, pipelineLogs, auditService);
    }

    @Test
    void purgesInBatchesAndWritesAuditEntry() {
        List<Long> fullBatch =
                LongStream.rangeClosed(1, DataRetentionService.BATCH_SIZE).boxed().toList();
        List<Long> tailBatch = LongStream.rangeClosed(501, 700).boxed().toList();
        when(aiInvocations.findIdsByCreatedAtBefore(any(), any(Pageable.class)))
                .thenReturn(fullBatch, tailBatch);
        when(auditLogs.findIdsByCreatedAtBefore(any(), any(Pageable.class))).thenReturn(List.of());
        when(pipelineLogs.findIdsByCreatedAtBefore(any(), any(Pageable.class)))
                .thenReturn(List.of());

        int deleted = service.purgeExpired();

        assertEquals(700, deleted);
        verify(aiInvocations, times(2)).deleteAllByIdInBatch(any());
        verify(auditService)
                .record(
                        eq(AuditAction.SETTINGS_UPDATE),
                        eq(DataRetentionService.TARGET_TYPE),
                        eq("retention"),
                        contains("ai_invocations=700"));
        // 默认保留 180 天：cutoff 应早于「现在 - 179 天」
        ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(aiInvocations, atLeastOnce())
                .findIdsByCreatedAtBefore(cutoff.capture(), any(Pageable.class));
        assertTrue(cutoff.getValue().isBefore(LocalDateTime.now().minusDays(179)));
    }

    @Test
    void tableFailureIsLoggedAndOtherTablesStillPurged() {
        when(aiInvocations.findIdsByCreatedAtBefore(any(), any(Pageable.class)))
                .thenThrow(new RuntimeException("db down"));
        List<Long> auditIds = List.of(9L, 10L);
        when(auditLogs.findIdsByCreatedAtBefore(any(), any(Pageable.class))).thenReturn(auditIds);
        when(pipelineLogs.findIdsByCreatedAtBefore(any(), any(Pageable.class)))
                .thenReturn(List.of());

        assertDoesNotThrow(() -> service.purgeExpired());

        verify(aiInvocations, never()).deleteAllByIdInBatch(any());
        verify(auditLogs).deleteAllByIdInBatch(auditIds);
    }
}
