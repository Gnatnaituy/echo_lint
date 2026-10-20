package com.echolint.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 知识库条目的检索分块（Batch 6）。
 *
 * <p>向量以 PostgreSQL 文本形式存储（{@code [0.1,0.2,...]}），配合 pgvector 的
 * <b>表达式索引</b> {@code ((embedding_text::vector(1024)) vector_cosine_ops)} 做 HNSW
 * 近似检索。这样 Hibernate 不需要自定义 UserType / PGobject，写入与读取都是普通 TEXT，
 * 索引与检索由原生 SQL 完成（见 {@code RagRetrievalService}）。</p>
 *
 * <p>表由 Flyway V2 创建（含唯一索引与 HNSW 索引）；Hibernate 只做校验/增量列更新。</p>
 */
@Entity
@Table(name = "knowledge_chunks")
@Getter
@Setter
public class KnowledgeChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属知识库条目 id（应用层维护，不加外键避免与 Hibernate 建表顺序耦合） */
    @Column(name = "entry_id", nullable = false)
    private Long entryId;

    /** 条目内分块序号（0 基） */
    @Column(name = "chunk_index", nullable = false)
    private int chunkIndex;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    /** sha256(content)，用于判断分块是否变化 */
    @Column(name = "content_hash", length = 64, nullable = false)
    private String contentHash;

    /** 向量文本形式；NULL 表示尚未向量化 */
    @Column(name = "embedding_text", columnDefinition = "TEXT")
    private String embeddingText;

    @Column(name = "embedding_model", length = 128)
    private String embeddingModel;

    @Column(name = "embedding_dim")
    private Integer embeddingDim;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

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
