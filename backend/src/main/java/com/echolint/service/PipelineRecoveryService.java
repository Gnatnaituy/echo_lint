package com.echolint.service;

import com.echolint.domain.RecordingStatus;
import com.echolint.entity.PipelineLog;
import com.echolint.entity.Recording;
import com.echolint.repository.PipelineLogRepository;
import com.echolint.repository.RecordingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 启动恢复（Batch 3b-1 起任务化）：
 *
 * <ol>
 *   <li>先把上次进程中断遗留的 RUNNING 任务重置为 PENDING（nextAttemptAt=now），任务不丢，
 *       由 {@code PipelineTaskScheduler} 在启动后自动续跑；</li>
 *   <li>再把「中断在中间态」且<b>没有活动任务</b>的录音置为 FAILED（历史数据/无任务记录），
 *       让用户可手动重新处理；仍有 PENDING/RUNNING 任务的录音交给任务队列恢复，不强制失败。</li>
 * </ol>
 *
 * <p>说明：独立成单独组件（ApplicationListener），避免 PipelineService 之类被代理的 Bean 承担事件职责。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PipelineRecoveryService implements ApplicationListener<ApplicationReadyEvent> {

    private static final List<RecordingStatus> INTERRUPTED_STATES = List.of(
            RecordingStatus.TRANSCRIBING, RecordingStatus.DFA_CHECKING, RecordingStatus.AI_CHECKING);

    private final RecordingRepository recordingRepository;
    private final PipelineLogRepository pipelineLogRepository;
    private final PipelineTaskService pipelineTaskService;

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        // 1) 任务优先：RUNNING -> PENDING，保证重启后任务继续被执行
        int recoveredTasks = pipelineTaskService.recoverRunningTasks();

        // 2) 录音状态：仅对没有活动任务的中断录音强制 FAILED
        List<Recording> interrupted = recordingRepository.findByStatusIn(INTERRUPTED_STATES);
        int failed = 0;
        int keptForTasks = 0;
        for (Recording recording : interrupted) {
            if (pipelineTaskService.hasActiveTask(recording.getId())) {
                keptForTasks++;
                log.info("启动恢复：录音 {} 仍有活动任务，交给任务队列续跑，不强制置为 FAILED", recording.getId());
                logStage(recording.getId(), "INFO", "服务重启：检测到活动任务，将由任务队列自动续跑");
                continue;
            }
            recording.setStatus(RecordingStatus.FAILED);
            recording.setErrorMessage("服务重启导致处理中断，可点击「重新处理」继续");
            recording.setProcessedTime(LocalDateTime.now());
            recordingRepository.save(recording);
            logStage(recording.getId(), "WARN", "检测到上次处理被中断（服务重启），已置为失败以便重新处理");
            failed++;
        }
        if (recoveredTasks > 0 || failed > 0 || keptForTasks > 0) {
            log.warn("启动恢复完成：重置 RUNNING 任务 {} 个；中断录音置为 FAILED {} 条，交由任务队列续跑 {} 条",
                    recoveredTasks, failed, keptForTasks);
        }
    }

    private void logStage(Long recordingId, String level, String message) {
        PipelineLog entry = new PipelineLog();
        entry.setRecordingId(recordingId);
        entry.setStage("PIPELINE");
        entry.setLevel(level);
        entry.setMessage(message);
        pipelineLogRepository.save(entry);
    }
}
