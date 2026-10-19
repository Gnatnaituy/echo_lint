package com.echolint.service;

import com.echolint.entity.PipelineTask;
import com.echolint.exception.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 流水线任务调度器（Batch 3b-1）。
 *
 * <p>每 5s 取一批到期 PENDING 任务：先用条件更新原子领取（PENDING -> RUNNING），
 * 再提交到 {@code pipelineExecutor} 执行。领取失败说明任务已被其它实例/线程拿走，直接跳过，
 * 因此多实例部署也不会重复执行同一条任务。</p>
 *
 * <p>执行失败由任务层记录（attempts/退避/死信），调度线程本身绝不向外抛异常，
 * 避免一次异常终止后续调度。</p>
 */
@Slf4j
@Component
public class PipelineTaskScheduler {

    private final PipelineTaskService taskService;
    private final PipelineService pipelineService;
    private final TaskExecutor pipelineExecutor;

    public PipelineTaskScheduler(PipelineTaskService taskService,
                                 PipelineService pipelineService,
                                 @Qualifier("pipelineExecutor") TaskExecutor pipelineExecutor) {
        this.taskService = taskService;
        this.pipelineService = pipelineService;
        this.pipelineExecutor = pipelineExecutor;
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
        try {
            pipelineService.processNow(task.getRecordingId());
            taskService.markSucceeded(taskId);
        } catch (BizException e) {
            // 录音已删除等不可恢复错误：直接终态 FAILED，不反复重试/告警
            if (e.getStatus() == HttpStatus.NOT_FOUND) {
                taskService.markFailedTerminal(taskId, e.getMessage());
            } else {
                taskService.markFailed(taskId, e.getMessage());
            }
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            log.warn("任务 {} 执行异常（录音 {}）：{}", taskId, task.getRecordingId(), message);
            taskService.markFailed(taskId, message);
        }
    }
}
