package com.recordaudit.repository;

import com.recordaudit.domain.CorpusLabel;
import com.recordaudit.entity.CorpusEntry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;

public interface CorpusEntryRepository extends JpaRepository<CorpusEntry, Long>, JpaSpecificationExecutor<CorpusEntry> {

    /** 取每个 label 最新的若干条（供 few-shot 使用，service 侧传 PageRequest.of(0, n)） */
    List<CorpusEntry> findByLabelOrderByIdDesc(CorpusLabel label, Pageable pageable);

    long countByLabel(CorpusLabel label);

    long countByRecordingId(Long recordingId);
}