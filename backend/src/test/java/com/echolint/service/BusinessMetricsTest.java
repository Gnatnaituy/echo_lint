package com.echolint.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.echolint.domain.RecordingStatus;
import com.echolint.domain.TaskStatus;
import com.echolint.repository.AiInvocationRepository;
import com.echolint.repository.PipelineTaskRepository;
import com.echolint.repository.RecordingRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

/** 业务指标（P2-31）：Gauge 值来自 repository count；查询异常回退 0。 */
class BusinessMetricsTest {

    @Test
    void gaugesReflectRepositoryCounts() {
        PipelineTaskRepository pipelineTasks = mock(PipelineTaskRepository.class);
        RecordingRepository recordings = mock(RecordingRepository.class);
        AiInvocationRepository aiInvocations = mock(AiInvocationRepository.class);
        when(pipelineTasks.countByStatus(TaskStatus.PENDING)).thenReturn(3L);
        when(pipelineTasks.countByStatus(TaskStatus.RUNNING)).thenReturn(1L);
        when(recordings.countByStatus(RecordingStatus.NEEDS_REVIEW)).thenReturn(7L);
        when(aiInvocations.countSuccessfulBetween(any(), any())).thenReturn(11L);
        when(aiInvocations.sumCostMicrosBetween(any(), any())).thenReturn(4321L);

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new BusinessMetrics(pipelineTasks, recordings, aiInvocations, registry);

        assertEquals(3.0, gauge(registry, "echolint.pipeline.tasks", "status", "PENDING"));
        assertEquals(1.0, gauge(registry, "echolint.pipeline.tasks", "status", "RUNNING"));
        assertEquals(7.0, registry.get("echolint.recordings.backlog").gauge().value());
        assertEquals(11.0, registry.get("echolint.ai.invocations.today").gauge().value());
        assertEquals(4321.0, registry.get("echolint.ai.cost.micros.today").gauge().value());
    }

    @Test
    void meterReadFailureFallsBackToZero() {
        PipelineTaskRepository pipelineTasks = mock(PipelineTaskRepository.class);
        RecordingRepository recordings = mock(RecordingRepository.class);
        AiInvocationRepository aiInvocations = mock(AiInvocationRepository.class);
        when(pipelineTasks.countByStatus(any())).thenThrow(new RuntimeException("db down"));
        when(recordings.countByStatus(any())).thenThrow(new RuntimeException("db down"));
        when(aiInvocations.countSuccessfulBetween(any(), any()))
                .thenThrow(new RuntimeException("db down"));

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new BusinessMetrics(pipelineTasks, recordings, aiInvocations, registry);

        assertEquals(0.0, gauge(registry, "echolint.pipeline.tasks", "status", "PENDING"));
        assertEquals(0.0, registry.get("echolint.recordings.backlog").gauge().value());
        assertEquals(0.0, registry.get("echolint.ai.invocations.today").gauge().value());
    }

    private static double gauge(
            SimpleMeterRegistry registry, String name, String tagKey, String tagValue) {
        return registry.get(name).tag(tagKey, tagValue).gauge().value();
    }
}
