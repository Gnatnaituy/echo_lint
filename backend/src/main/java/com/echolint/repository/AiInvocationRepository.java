package com.echolint.repository;

import com.echolint.entity.AiInvocation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AiInvocationRepository extends JpaRepository<AiInvocation, Long> {

    /** 某条录音的全部 AI 调用，按时间正序（复筛在前、挖掘在后） */
    List<AiInvocation> findByRecordingIdOrderByIdAsc(Long recordingId);
}
