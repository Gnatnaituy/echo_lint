package com.echolint.repository;

import com.echolint.domain.TaskStatus;
import com.echolint.entity.PipelineTask;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PipelineTaskRepository extends JpaRepository<PipelineTask, Long>,
        JpaSpecificationExecutor<PipelineTask> {

    /** 幂等入队：取该录音最近一条 PENDING/RUNNING 任务 */
    Optional<PipelineTask> findFirstByRecordingIdAndStatusInOrderByIdDesc(Long recordingId,
                                                                         Collection<TaskStatus> statuses);

    /** 启动恢复/重试判断：该录音是否存在活动任务 */
    boolean existsByRecordingIdAndStatusIn(Long recordingId, Collection<TaskStatus> statuses);

    /** 调度器取到期任务（最多 limit 条） */
    @Query("select t from PipelineTask t where t.status = :status and t.nextAttemptAt <= :now "
            + "order by t.nextAttemptAt asc, t.id asc")
    List<PipelineTask> findDue(@Param("status") TaskStatus status, @Param("now") LocalDateTime now,
                               Pageable pageable);

    /**
     * 原子领取：仅当任务仍为 PENDING 时置为 RUNNING。返回 1 表示领取成功。
     * 条件更新是多实例/重入下的去重手段，避免同一条任务被并发执行。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update PipelineTask t set t.status = com.echolint.domain.TaskStatus.RUNNING, t.updatedAt = :now "
            + "where t.id = :id and t.status = com.echolint.domain.TaskStatus.PENDING")
    int markRunning(@Param("id") Long id, @Param("now") LocalDateTime now);

    /** 启动恢复：进程中断遗留的 RUNNING 任务重置为 PENDING 立即可跑，不丢任务 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update PipelineTask t set t.status = com.echolint.domain.TaskStatus.PENDING, "
            + "t.nextAttemptAt = :now, t.updatedAt = :now "
            + "where t.status = com.echolint.domain.TaskStatus.RUNNING")
    int recoverRunning(@Param("now") LocalDateTime now);

    /** 提交线程池失败时把已领取的 RUNNING 任务退回 PENDING，等待下个调度周期 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update PipelineTask t set t.status = com.echolint.domain.TaskStatus.PENDING, "
            + "t.nextAttemptAt = :now, t.updatedAt = :now "
            + "where t.id = :id and t.status = com.echolint.domain.TaskStatus.RUNNING")
    int requeueRunning(@Param("id") Long id, @Param("now") LocalDateTime now);
}
