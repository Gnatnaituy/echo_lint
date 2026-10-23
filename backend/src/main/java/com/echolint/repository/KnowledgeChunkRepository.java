package com.echolint.repository;

import com.echolint.entity.KnowledgeChunk;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface KnowledgeChunkRepository extends JpaRepository<KnowledgeChunk, Long> {

    List<KnowledgeChunk> findByEntryIdOrderByChunkIndexAsc(Long entryId);

    /** 索引明细：条目内分块按 chunkIndex 升序分页（排序由 Pageable 指定） */
    Page<KnowledgeChunk> findByEntryId(Long entryId, Pageable pageable);

    /** 条目第一个分块：索引明细展示 embedding 模型/维度 */
    Optional<KnowledgeChunk> findFirstByEntryIdOrderByChunkIndexAsc(Long entryId);

    long countByEntryId(Long entryId);

    @Transactional
    void deleteByEntryId(Long entryId);
}
