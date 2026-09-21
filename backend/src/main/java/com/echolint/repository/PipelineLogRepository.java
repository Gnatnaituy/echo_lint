package com.echolint.repository;

import com.echolint.entity.PipelineLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PipelineLogRepository extends JpaRepository<PipelineLog, Long> {

    List<PipelineLog> findByRecordingIdOrderByCreatedAtAscIdAsc(Long recordingId);
}