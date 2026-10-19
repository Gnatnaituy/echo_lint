package com.echolint.service;

import com.echolint.domain.NotifyEvent;
import com.echolint.domain.TaskStatus;
import com.echolint.dto.PipelineTaskView;
import com.echolint.entity.PipelineTask;
import com.echolint.entity.Recording;
import com.echolint.exception.BizException;
import com.echolint.repository.PipelineTaskRepository;
import com.echolint.repository.RecordingRepository;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 异步流水线任务队列（Batch 3b-1）。
 *
 * <p>可靠性设计：</p>
 * <ul>
 *   <li>任务持久化在 {@code pipeline_tasks}，服务重启后 RUNNING 任务由启动恢复重置为 PENDING；</li>
 *   <li>入队幂等：同一录音已有 PENDING/RUNNING 任务时直接复用，不重复排队；</li>
 *   <li>领取使用条件更新（PENDING -> RUNNING），多实例/重入下同一条任务只会被执行一次；</li>
 *   <li>失败退避 30s/120s/300s（封顶），达到 max_attempts 进入 DEAD；只有 DEAD 才发
 *       RECORDING_FAILED 通知，避免每次重试都告警；</li>
 *   <li>所有状态变更都在事务方法内完成，调度器（{@link PipelineTaskScheduler}）只负责编排。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PipelineTaskService {

    /** 单次调度最多领取的任务数 */
    public static final int BATCH_SIZE = 5;

    /** 退避序列（秒）；超出按最后一档封顶 */
    static final long[] RETRY_BACKOFF_SECONDS = {30, 120, 300};

    /** 活动任务状态：入队幂等与启动恢复的判断依据 */
    static final List<TaskStatus> ACTIVE_STATUSES = List.of(TaskStatus.PENDING, TaskStatus.RUNNING);

    private static final int MAX_ERROR_LENGTH = 2000;

    private final PipelineTaskRepository taskRepository;
    private final RecordingRepository recordingRepository;
    private final NotificationService notificationService;

    /**
     * 入队（幂等）：该录音已有 PENDING/RUNNING 任务时直接返回已有任务。
     *
     * <p>用录音行锁串行化同一录音的并发入队（上传/重试/多实例），避免产生重复活动任务；
     * 任务本身没有唯一约束（MySQL 不支持部分索引），行锁是简单可靠的去重手段。</p>
     */
    @Transactional
    public PipelineTask enqueue(Long recordingId) {
        if (recordingId == null) {
            throw new BizException("录音 ID 不能为空");
        }
        recordingRepository.findWithLockById(recordingId)
                .orElseThrow(() -> new BizException("录音不存在: " + recordingId, HttpStatus.NOT_FOUND));

        Optional<PipelineTask> active = taskRepository.findFirstByRecordingIdAndStatusInOrderByIdDesc(
                recordingId, ACTIVE_STATUSES);
        if (active.isPresent()) {
            log.debug("录音 {} 已有活动任务 {}（{}），入队幂等返回", recordingId,
                    active.get().getId(), active.get().getStatus());
            return active.get();
        }

        PipelineTask task = new PipelineTask();
        task.setRecordingId(recordingId);
        task.setStatus(TaskStatus.PENDING);
        task.setAttempts(0);
        task.setMaxAttempts(PipelineTask.DEFAULT_MAX_ATTEMPTS);
        task.setNextAttemptAt(LocalDateTime.now());
        PipelineTask saved = taskRepository.save(task);
        log.info("录音 {} 已入队任务 {}", recordingId, saved.getId());
        return saved;
    }

    /** 调度器查询到期任务（最多 {@link #BATCH_SIZE} 条） */
    @Transactional(readOnly = true)
    public List<PipelineTask> findDueTasks() {
        return taskRepository.findDue(TaskStatus.PENDING, LocalDateTime.now(), PageRequest.of(0, BATCH_SIZE));
    }

    public Optional<PipelineTask> get(Long taskId) {
        return taskRepository.findById(taskId);
    }

    /**
     * 原子领取任务：PENDING -> RUNNING。返回 true 表示领取成功、可以提交执行。
     * 已被其它调度实例/线程领取时返回 false，调用方必须跳过。
     */
    @Transactional
    public boolean claim(Long taskId) {
        return taskRepository.markRunning(taskId, LocalDateTime.now()) == 1;
    }

    /** 执行成功：RUNNING -> SUCCEEDED */
    @Transactional
    public PipelineTask markSucceeded(Long taskId) {
        PipelineTask task = taskRepository.findById(taskId).orElse(null);
        if (task == null) {
            return null;
        }
        LocalDateTime now = LocalDateTime.now();
        task.setStatus(TaskStatus.SUCCEEDED);
        task.setFinishedAt(now);
        task.setLastError(null);
        task.setNextAttemptAt(null);
        PipelineTask saved = taskRepository.save(task);
        log.info("任务 {} 执行成功（录音 {}）", taskId, task.getRecordingId());
        return saved;
    }

    /**
     * 执行失败：attempts+1；未达上限 -> PENDING + 指数退避；达到上限 -> DEAD（死信）。
     * 只有进入 DEAD 才发 RECORDING_FAILED 通知，中间重试不重复告警。
     */
    @Transactional
    public PipelineTask markFailed(Long taskId, String error) {
        PipelineTask task = taskRepository.findById(taskId).orElse(null);
        if (task == null) {
            log.warn("任务 {} 不存在，无法记录失败: {}", taskId, error);
            return null;
        }
        LocalDateTime now = LocalDateTime.now();
        int attempts = task.getAttempts() + 1;
        task.setAttempts(attempts);
        task.setLastError(truncate(error));

        if (attempts < task.getMaxAttempts()) {
            task.setStatus(TaskStatus.PENDING);
            task.setNextAttemptAt(now.plusSeconds(backoffSeconds(attempts)));
            task.setFinishedAt(null);
            log.warn("任务 {} 第 {} 次失败，{}s 后重试（录音 {}）：{}", taskId, attempts,
                    backoffSeconds(attempts), task.getRecordingId(), truncate(error));
        } else {
            task.setStatus(TaskStatus.DEAD);
            task.setNextAttemptAt(null);
            task.setFinishedAt(now);
            log.warn("任务 {} 重试耗尽进入死信（录音 {}，共 {} 次）：{}", taskId,
                    task.getRecordingId(), attempts, truncate(error));
        }
        PipelineTask saved = taskRepository.save(task);
        if (saved.getStatus() == TaskStatus.DEAD) {
            notifyDead(saved);
        }
        return saved;
    }

    /** 不可重试的失败（例如录音已被删除）：直接终态 FAILED，不反复告警 */
    @Transactional
    public PipelineTask markFailedTerminal(Long taskId, String error) {
        PipelineTask task = taskRepository.findById(taskId).orElse(null);
        if (task == null) {
            return null;
        }
        LocalDateTime now = LocalDateTime.now();
        task.setStatus(TaskStatus.FAILED);
        task.setAttempts(task.getAttempts() + 1);
        task.setLastError(truncate(error));
        task.setNextAttemptAt(null);
        task.setFinishedAt(now);
        PipelineTask saved = taskRepository.save(task);
        log.warn("任务 {} 不可重试失败（录音 {}）：{}", taskId, task.getRecordingId(), truncate(error));
        return saved;
    }

    /** 提交线程池失败（极少数情况）：RUNNING 退回 PENDING，下个调度周期重试 */
    @Transactional
    public void requeue(Long taskId) {
        int updated = taskRepository.requeueRunning(taskId, LocalDateTime.now());
        if (updated > 0) {
            log.warn("任务 {} 提交执行失败，已退回 PENDING", taskId);
        }
    }

    /**
     * 管理员手动重试死信/终态失败任务：DEAD/FAILED -> PENDING，attempts 归零。
     * 对应录音的 FAILED 状态由流水线下一次执行覆盖。
     */
    @Transactional
    public PipelineTaskView retry(Long taskId) {
        PipelineTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> new BizException("任务不存在: " + taskId, HttpStatus.NOT_FOUND));
        if (task.getStatus() != TaskStatus.DEAD && task.getStatus() != TaskStatus.FAILED) {
            throw new BizException("仅 DEAD/FAILED 任务可重试（当前 " + task.getStatus() + "）",
                    HttpStatus.CONFLICT);
        }
        task.setStatus(TaskStatus.PENDING);
        task.setAttempts(0);
        task.setNextAttemptAt(LocalDateTime.now());
        task.setLastError(null);
        task.setFinishedAt(null);
        PipelineTask saved = taskRepository.save(task);
        log.info("任务 {} 已由管理员重置为 PENDING（录音 {}）", taskId, saved.getRecordingId());
        return viewOf(saved);
    }

    /**
     * 启动恢复：把上次进程中断遗留的 RUNNING 任务重置为 PENDING（nextAttemptAt=now）。
     * 在 {@code PipelineRecoveryService} 里先于「强制 FAILED」执行，保证任务不丢。
     */
    @Transactional
    public int recoverRunningTasks() {
        int recovered = taskRepository.recoverRunning(LocalDateTime.now());
        if (recovered > 0) {
            log.warn("启动恢复：{} 个 RUNNING 任务已重置为 PENDING，等待调度重试", recovered);
        }
        return recovered;
    }

    /** 该录音是否存在 PENDING/RUNNING 任务（启动恢复用） */
    public boolean hasActiveTask(Long recordingId) {
        return taskRepository.existsByRecordingIdAndStatusIn(recordingId, ACTIVE_STATUSES);
    }

    /** 该录音是否有正在执行的任务（录音手动重试的并发保护） */
    public boolean isRunning(Long recordingId) {
        return taskRepository.existsByRecordingIdAndStatusIn(recordingId, List.of(TaskStatus.RUNNING));
    }

    /** 任务分页（含录音文件名） */
    @Transactional(readOnly = true)
    public Page<PipelineTaskView> query(TaskStatus status, Long recordingId, Pageable pageable) {
        Specification<PipelineTask> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (recordingId != null) {
                predicates.add(cb.equal(root.get("recordingId"), recordingId));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
        Page<PipelineTask> page = taskRepository.findAll(spec, pageable);
        Map<Long, String> fileNames = fileNames(
                page.getContent().stream().map(PipelineTask::getRecordingId).collect(Collectors.toSet()));
        return page.map(task -> PipelineTaskView.from(task, fileNames.get(task.getRecordingId())));
    }

    /** 解析任务状态筛选参数（非法值 -> 400） */
    public static TaskStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return TaskStatus.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BizException("任务状态不合法: " + raw);
        }
    }

    private PipelineTaskView viewOf(PipelineTask task) {
        String fileName = fileNames(Set.of(task.getRecordingId())).get(task.getRecordingId());
        return PipelineTaskView.from(task, fileName);
    }

    private Map<Long, String> fileNames(Collection<Long> recordingIds) {
        if (recordingIds == null || recordingIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> names = new HashMap<>();
        for (Recording recording : recordingRepository.findAllById(recordingIds)) {
            names.put(recording.getId(), recording.getFileName());
        }
        return names;
    }

    /** 退避秒数：第 1 次失败 30s、第 2 次 120s、第 3 次及以后 300s 封顶 */
    static long backoffSeconds(int attempts) {
        int index = Math.min(Math.max(attempts - 1, 0), RETRY_BACKOFF_SECONDS.length - 1);
        return RETRY_BACKOFF_SECONDS[index];
    }

    private void notifyDead(PipelineTask task) {
        try {
            String fileName = recordingRepository.findById(task.getRecordingId())
                    .map(Recording::getFileName)
                    .orElse("未知文件");
            notificationService.notify(NotifyEvent.RECORDING_FAILED, task.getRecordingId(),
                    "录音处理失败（重试耗尽）",
                    "录音 #" + task.getRecordingId() + "（" + fileName + "）处理失败，已重试 "
                            + task.getAttempts() + " 次后进入死信：" + task.getLastError(), null);
        } catch (Exception e) {
            // 通知是旁路能力，失败不影响任务状态
            log.warn("死信通知发送失败（task={}）：{}", task.getId(), e.getMessage());
        }
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        String text = value.trim();
        return text.length() <= MAX_ERROR_LENGTH ? text : text.substring(0, MAX_ERROR_LENGTH);
    }
}
