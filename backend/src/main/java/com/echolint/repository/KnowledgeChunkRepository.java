package com.echolint.repository;

import com.echolint.entity.KnowledgeChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface KnowledgeChunkRepository extends JpaRepository<KnowledgeChunk, Long> {

    List<KnowledgeChunk> findByEntryIdOrderByChunkIndexAsc(Long entryId);

    long countByEntryId(Long entryId);

    @Transactional
    void deleteByEntryId(Long entryId);
}
