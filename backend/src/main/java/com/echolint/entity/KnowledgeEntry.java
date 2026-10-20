package com.echolint.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 知识库 / SOP 条目（Batch 4a）：供人工复核与 AI 复筛参考的合规知识。
 */
@Entity
@Table(name = "knowledge_entries", indexes = {
        @Index(name = "idx_knowledge_category", columnList = "category"),
        @Index(name = "idx_knowledge_enabled", columnList = "enabled")
})
@Getter
@Setter
public class KnowledgeEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String title;

    /** 分类（如 话术规范 / 违规示例 / 处罚标准），可空 */
    @Column(length = 64)
    private String category;

    /** 关联违规类型（ViolationType code），可空 */
    @Column(name = "violation_type", length = 64)
    private String violationType;

    /** 正文（Markdown/纯文本） */
    @Column(columnDefinition = "TEXT")
    private String content;

    /** 逗号分隔标签，便于检索 */
    @Column(length = 512)
    private String tags;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "created_by", length = 64)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    // ------------------------------------------------------------------
    // RAG 索引状态（Batch 6）：条目写入后异步分块 + 向量化
    // ------------------------------------------------------------------

    /** 来源：MANUAL（手工录入）/ DOCUMENT（文档导入） */
    @Column(name = "source_type", length = 16, nullable = false)
    private String sourceType = "MANUAL";

    /** 文档导入的原始文件名（手工条目为空） */
    @Column(name = "source_filename", length = 255)
    private String sourceFilename;

    /** 文档内容 SHA-256：同一文件重复导入时返回既有条目；唯一约束允许多个 NULL（手工条目） */
    @JsonIgnore
    @Column(name = "source_sha256", length = 64, unique = true)
    private String sourceSha256;

    /** 文档原始字节数 */
    @Column(name = "source_size")
    private Long sourceSize;

    /** PENDING / PARSING / INDEXING / READY / FAILED */
    @Column(name = "index_status", length = 16, nullable = false)
    private String indexStatus = "PENDING";

    /** 索引失败原因（READY 时为空） */
    @Column(name = "index_error", columnDefinition = "TEXT")
    private String indexError;

    @Column(name = "indexed_at")
    private LocalDateTime indexedAt;

    /** 参与 embedding 的内容指纹（标题+分类+正文），未变化时跳过重复索引 */
    @JsonIgnore
    @Column(name = "embedding_hash", length = 64)
    private String embeddingHash;

    /** 当前分块数（READY 时为实际 chunk 数） */
    @Column(name = "chunk_count", nullable = false)
    private int chunkCount = 0;

    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
