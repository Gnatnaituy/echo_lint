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
 * 启动恢复：把上次进程中断在中间态的录音置为 FAILED，让用户可手动「重新处理」。
 *
 * 说明：刻意独立成单独组件，而不是让 PipelineService 实现 ApplicationListener ——
 * PipelineService 有 @Async 方法，若它同时实现接口，Spring 会使用 JDK 动态代理，
 * 导致按具体类型注入失败（Bean named 'pipelineService' ... was actually of type 'jdk.proxy2.$Proxy'）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PipelineRecoveryService implements ApplicationListener<ApplicationReadyEvent> {

    private static final List<RecordingStatus> INTERRUPTED_STATES = List.of(
            RecordingStatus.TRANSCRIBING, RecordingStatus.DFA_CHECKING, RecordingStatus.AI_CHECKING);

    private final RecordingRepository recordingRepository;
    private final PipelineLogRepository pipelineLogRepository;

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        List<Recording> interrupted = recordingRepository.findByStatusIn(INTERRUPTED_STATES);
        for (Recording recording : interrupted) {
            recording.setStatus(RecordingStatus.FAILED);
            recording.setErrorMessage("服务重启导致处理中断，可点击「重新处理」继续");
            recording.setProcessedTime(LocalDateTime.now());
            recordingRepository.save(recording);
            logStage(recording.getId(),
                    "检测到上次处理被中断（服务重启），已置为失败以便重新处理");
        }
        if (!interrupted.isEmpty()) {
            log.warn("启动恢复：{} 条录音的处理被中断，已置为 FAILED 等待重新处理", interrupted.size());
        }
    }

    private void logStage(Long recordingId, String message) {
        PipelineLog entry = new PipelineLog();
        entry.setRecordingId(recordingId);
        entry.setStage("PIPELINE");
        entry.setLevel("WARN");
        entry.setMessage(message);
        pipelineLogRepository.save(entry);
    }
}
