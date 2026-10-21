package com.echolint.repository;

import com.echolint.entity.KnowledgeEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;

public interface KnowledgeEntryRepository extends JpaRepository<KnowledgeEntry, Long>,
        JpaSpecificationExecutor<KnowledgeEntry> {

    /** 文档导入去重：同一内容 SHA-256 只保留一个条目 */
    Optional<KnowledgeEntry> findFirstBySourceSha256(String sourceSha256);

    /** 启动回填/重建索引：非 READY 的条目 */
    List<KnowledgeEntry> findByIndexStatusNot(String indexStatus);

    long countByIndexStatus(String indexStatus);
}
