package com.echolint.repository;

import com.echolint.domain.RecordingStatus;
import com.echolint.entity.Recording;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface RecordingRepository extends JpaRepository<Recording, Long>, JpaSpecificationExecutor<Recording> {

    @Query("select r.status, count(r) from Recording r group by r.status")
    List<Object[]> countGroupByStatus();

    @Query(value = "SELECT DATE(upload_time) AS d, COUNT(*) AS c FROM recordings " +
            "WHERE upload_time >= :since GROUP BY DATE(upload_time) ORDER BY d", nativeQuery = true)
    List<Object[]> countByDaySince(@Param("since") LocalDateTime since);

    @Query("select count(r) from Recording r where r.uploadTime >= :since")
    long countUploadedSince(@Param("since") LocalDateTime since);

    /** 用于启动时恢复：找出中断在中间状态的录音 */
    List<Recording> findByStatusIn(List<RecordingStatus> statuses);

    /** 待复核积压检查（每日通知）与工作台统计用 */
    long countByStatus(RecordingStatus status);

    /** 上传去重：按音频内容 SHA-256 查找已存在的录音 */
    Optional<Recording> findByAudioSha256(String audioSha256);

    /*
     * 报表统计（Batch 2a）：时间口径统一为 coalesce(callTime, uploadTime)，
     * 即优先按通话时间，通话时间为空的历史数据回退上传时间。
     */

    @Query("select r.status, count(r) from Recording r " +
            "where coalesce(r.callTime, r.uploadTime) >= :from " +
            "and coalesce(r.callTime, r.uploadTime) < :to group by r.status")
    List<Object[]> countByStatusBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query("select r.violationType, count(r) from Recording r " +
            "where r.status = com.echolint.domain.RecordingStatus.VIOLATION_CONFIRMED " +
            "and coalesce(r.callTime, r.uploadTime) >= :from " +
            "and coalesce(r.callTime, r.uploadTime) < :to group by r.violationType")
    List<Object[]> countConfirmedByViolationTypeBetween(@Param("from") LocalDateTime from,
                                                       @Param("to") LocalDateTime to);

    /** 抽样复核录音数（Batch 2b-2）：时间口径与其它报表统计一致 */
    @Query("select count(r) from Recording r where r.sampled = true " +
            "and coalesce(r.callTime, r.uploadTime) >= :from " +
            "and coalesce(r.callTime, r.uploadTime) < :to")
    long countSampledBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query("select r.agentName, count(r) from Recording r " +
            "where coalesce(r.callTime, r.uploadTime) >= :from " +
            "and coalesce(r.callTime, r.uploadTime) < :to " +
            "group by r.agentName order by count(r) desc, r.agentName asc")
    List<Object[]> countByAgentBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
                                       Pageable pageable);

    @Query("select r.teamName, count(r) from Recording r " +
            "where coalesce(r.callTime, r.uploadTime) >= :from " +
            "and coalesce(r.callTime, r.uploadTime) < :to " +
            "group by r.teamName order by count(r) desc, r.teamName asc")
    List<Object[]> countByTeamBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
                                      Pageable pageable);

    @Query(value = "SELECT DATE(COALESCE(call_time, upload_time)) AS d, COUNT(*) AS c FROM recordings " +
            "WHERE COALESCE(call_time, upload_time) >= :from " +
            "AND COALESCE(call_time, upload_time) < :to " +
            "GROUP BY DATE(COALESCE(call_time, upload_time)) ORDER BY d", nativeQuery = true)
    List<Object[]> countByDayBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);
}
