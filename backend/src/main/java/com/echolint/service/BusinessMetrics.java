package com.echolint.service;

import com.echolint.domain.RecordingStatus;
import com.echolint.domain.TaskStatus;
import com.echolint.repository.AiInvocationRepository;
import com.echolint.repository.PipelineTaskRepository;
import com.echolint.repository.RecordingRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.function.LongSupplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 业务可观测性指标（审计 P2-31，仅后端部分）。
 *
 * <p>把「异步任务队列 / 复核积压 / AI 用量」这类业务健康度暴露为 Micrometer Gauge， 由 {@code /actuator/prometheus} 采集。只新增
 * Bean、通过 repository count 查询， 不改动流水线/AI 服务本身。
 *
 * <p>Gauge 的取值函数在每次指标抓取时执行：查询异常一律回退 0 并打 debug 日志， 绝不能让指标采集失败影响 Actuator 或业务。
 */
@Slf4j
@Component
public class BusinessMetrics {

    /** 指标前缀（Prometheus 会把点转为下划线：echolint_pipeline_tasks 等） */
    static final String PREFIX = "echolint.";

    public BusinessMetrics(
            PipelineTaskRepository pipelineTasks,
            RecordingRepository recordings,
            AiInvocationRepository aiInvocations,
            MeterRegistry registry) {
        // 1) 流水线任务数：按状态区分（PENDING/RUNNING 看排队与在途，DEAD 看死信积压）
        for (TaskStatus status : TaskStatus.values()) {
            Gauge.builder(
                            PREFIX + "pipeline.tasks",
                            pipelineTasks,
                            repo -> safeValue(() -> repo.countByStatus(status)))
                    .description("流水线任务数（按状态）")
                    .tag("status", status.name())
                    .register(registry);
        }

        // 2) 待人工复核积压：值班最关心的业务队列深度
        Gauge.builder(
                        PREFIX + "recordings.backlog",
                        recordings,
                        repo -> safeValue(() -> repo.countByStatus(RecordingStatus.NEEDS_REVIEW)))
                .description("待人工复核积压数（status=NEEDS_REVIEW）")
                .register(registry);

        // 3) 今日 AI 成功调用数与成本（微美元）：提供可直接告警的实时值
        Gauge.builder(
                        PREFIX + "ai.invocations.today",
                        aiInvocations,
                        repo -> safeValue(() -> todayInvocations(repo)))
                .description("今日 AI 成功调用次数")
                .register(registry);
        Gauge.builder(
                        PREFIX + "ai.cost.micros.today",
                        aiInvocations,
                        repo -> safeValue(() -> todayCostMicros(repo)))
                .description("今日 AI 调用成本（微美元，1e-6 USD）")
                .register(registry);
    }

    /** 今日（自然日）成功调用次数 */
    private static long todayInvocations(AiInvocationRepository repository) {
        LocalDateTime start = LocalDate.now().atStartOfDay();
        return repository.countSuccessfulBetween(start, start.plusDays(1));
    }

    /** 今日（自然日）成本合计（微美元） */
    private static long todayCostMicros(AiInvocationRepository repository) {
        LocalDateTime start = LocalDate.now().atStartOfDay();
        Long cost = repository.sumCostMicrosBetween(start, start.plusDays(1));
        return cost == null ? 0 : cost;
    }

    /** 指标取值兜底：任何查询异常都退回 0，避免 Actuator 抓取失败 */
    private static double safeValue(LongSupplier supplier) {
        try {
            return supplier.getAsLong();
        } catch (Exception e) {
            log.debug("读取业务指标失败，按 0 处理：{}", e.getMessage());
            return 0;
        }
    }
}
