package com.echolint.entity;

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
