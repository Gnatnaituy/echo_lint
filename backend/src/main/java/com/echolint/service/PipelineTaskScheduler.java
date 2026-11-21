package com.echolint.service;

import com.echolint.entity.PipelineTask;
import com.echolint.exception.BizException;
import com.echolint.util.TraceContinuation;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 流水线任务调度器（Batch 3b-1）。
 *
 * <p>每 5s 取一批到期 PENDING 任务：先用条件更新原子领取（PENDING -> RUNNING）， 再提交到 {@code pipelineExecutor}
 * 执行。领取失败说明任务已被其它实例/线程拿走，直接跳过， 因此多实例部署也不会重复执行同一条任务。
 *
 * <p>执行失败由任务层记录（attempts/退避/死信），调度线程本身绝不向外抛异常， 避免一次异常终止后续调度。
 *
 * <p>线程池拒绝（{@code pipelineExecutor} 用 AbortPolicy，队列满时抛 {@link
 * java.util.concurrent.RejectedExecutionException}）时把刚领取的任务退回 PENDING， 下个周期再派发，绝不让调度线程亲自执行流水线。
 */
@Slf4j
@Component
public class PipelineTaskScheduler {

    private final PipelineTaskService taskService;
    private final PipelineService pipelineService;
    private final TaskExecutor pipelineExecutor;

    /** OTel SDK 入口：执行任务时用落库 traceparent 恢复 remote parent（见 TraceContinuation） */
    private final OpenTelemetry openTelemetry;

    /** Observation 栈：续接执行时临时摘除调度线程传播的 Observation（见 execute 注释） */
    private final ObservationRegistry observationRegistry;

    public PipelineTaskScheduler(
            PipelineTaskService taskService,
            PipelineService pipelineService,
            @Qualifier("pipelineExecutor") TaskExecutor pipelineExecutor,
            OpenTelemetry openTelemetry,
            ObservationRegistry observationRegistry) {
        this.taskService = taskService;
        this.pipelineService = pipelineService;
        this.pipelineExecutor = pipelineExecutor;
        this.openTelemetry = openTelemetry;
        this.observationRegistry = observationRegistry;
    }

    /** 固定延迟 5s：上一次调度结束后再等 5s，避免慢查询把调度线程堆满 */
    @Scheduled(fixedDelay = 5000)
    public void dispatchDueTasks() {
        List<PipelineTask> due;
        try {
            due = taskService.findDueTasks();
        } catch (Exception e) {
            // 数据库抖动等：本轮放弃，下个周期重试，绝不能把调度线程抛崩
            log.warn("任务调度查询失败（下个周期重试）：{}", e.getMessage());
            return;
        }
        for (PipelineTask task : due) {
            Long taskId = task.getId();
            try {
                if (!taskService.claim(taskId)) {
                    log.debug("任务 {} 已被其它调度领取，跳过", taskId);
                    continue;
                }
                pipelineExecutor.execute(() -> execute(taskId));
            } catch (Exception e) {
                // 线程池拒绝/关闭等极少数情况：把已领取的任务退回 PENDING，避免卡在 RUNNING
                log.warn("任务 {} 提交执行失败，退回 PENDING：{}", taskId, e.getMessage());
                try {
                    taskService.requeue(taskId);
                } catch (Exception requeueError) {
                    log.warn("任务 {} 退回 PENDING 失败：{}", taskId, requeueError.getMessage());
                }
            }
        }
    }

    /** 在 pipelineExecutor 线程执行单条任务；成功/失败都回写任务状态 */
    void execute(Long taskId) {
        PipelineTask task = taskService.get(taskId).orElse(null);
        if (task == null) {
            log.warn("任务 {} 已不存在，跳过执行", taskId);
            return;
        }
        // trace 续接：把入队时捕获的 traceparent 恢复为 remote parent，开「流水线执行」span，
        // 与上传请求在 Jaeger 里连成同一条 trace。
        Span span =
                TraceContinuation.startPipelineSpan(openTelemetry, task.getTraceparent(), taskId);
        if (span == null) {
            // 无 traceparent（历史数据 / tracing 未启用）：沿用 TaskDecorator 传播的调度
            // 上下文，行为与未接入 tracing 前一致
            doExecute(task, null);
            return;
        }
        // 续接执行前临时摘除 Observation 栈：三阶段 Observation（PipelineService.observe）的
        // parent 默认取栈顶 Observation——TaskDecorator 已把调度器的 Observation 恢复到本线程，
        // 不摘会把阶段 span 挂进调度器的 trace。摘栈后阶段 Observation 走 tracer.nextSpan()
        // → parent = OTel current = 上面恢复的 remote parent span。结束时还原调度上下文，
        // 不影响 decorator 自身的快照恢复。
        Observation.Scope outer = observationRegistry.getCurrentObservationScope();
        observationRegistry.setCurrentObservationScope(null);
        try (Scope ignored = span.makeCurrent()) {
            doExecute(task, span);
        } finally {
            observationRegistry.setCurrentObservationScope(outer);
            span.end();
        }
    }

    /** 任务执行主体；span 非 null（续接执行）时异常记入 span */
    private void doExecute(PipelineTask task, Span span) {
        Long taskId = task.getId();
        try {
            pipelineService.processNow(task.getRecordingId());
            taskService.markSucceeded(taskId);
        } catch (BizException e) {
            recordOn(span, e);
            // 录音已删除等不可恢复错误：直接终态 FAILED，不反复重试/告警
            if (e.getStatus() == HttpStatus.NOT_FOUND) {
                taskService.markFailedTerminal(taskId, e.getMessage());
            } else {
                taskService.markFailed(taskId, e.getMessage());
            }
        } catch (Exception e) {
            recordOn(span, e);
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            log.warn("任务 {} 执行异常（录音 {}）：{}", taskId, task.getRecordingId(), message);
            taskService.markFailed(taskId, message);
        }
    }

    /** 失败原因记进 span（异常事件），span 为 null（无续接）时 no-op */
    private static void recordOn(Span span, Exception e) {
        if (span != null) {
            span.recordException(e);
        }
    }
}
