package com.echolint.repository;

import com.echolint.entity.ReviewRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ReviewRecordRepository extends JpaRepository<ReviewRecord, Long> {

    /** 复核历史，按轮次正序（同一轮多提交时按 id 正序） */
    List<ReviewRecord> findByRecordingIdOrderByRoundAscIdAsc(Long recordingId);

    /** 最近一次复核，用于「双人复核时同一用户不能连续复核」校验 */
    Optional<ReviewRecord> findTopByRecordingIdOrderByRoundDescIdDesc(Long recordingId);

    void deleteByRecordingId(Long recordingId);
}
