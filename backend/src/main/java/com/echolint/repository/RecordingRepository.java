package com.echolint.repository;

import com.echolint.domain.RecordingStatus;
import com.echolint.entity.Recording;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

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
}