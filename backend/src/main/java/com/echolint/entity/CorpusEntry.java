package com.echolint.entity;

import com.echolint.domain.CorpusLabel;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 语料库：人工复检结论沉淀，用于复筛 few-shot 提示词
 */
@Entity
@Table(name = "corpus_entries", indexes = {
        @Index(name = "idx_corpus_label", columnList = "label")
})
@Getter
@Setter
public class CorpusEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private CorpusLabel label;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String transcript;

    @Column(length = 32)
    private String violationType;

    @Column(columnDefinition = "TEXT")
    private String reason;

    @Column(length = 32)
    private String source = "REVIEW";

    /** 来源录音，可为空（人工录入） */
    private Long recordingId;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    // ------------------------------------------------------------------
    // 语义 few-shot（Batch 6，Phase A）：人工复检语料的向量表示
    // ------------------------------------------------------------------

    /** 转写文本截断后的 embedding 文本形式（[0.1,0.2,...]）；内部字段，不通过 API 返回 */
    @JsonIgnore
    @Column(columnDefinition = "TEXT")
    private String embeddingText;

    @JsonIgnore
    @Column(length = 128)
    private String embeddingModel;

    /** 转写文本的 SHA-256，未变化时跳过重复向量化；内部字段 */
    @JsonIgnore
    @Column(length = 64)
    private String embeddingHash;

    @JsonIgnore
    private LocalDateTime embeddingUpdatedAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}