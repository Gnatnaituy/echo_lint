package com.recordaudit.entity;

import com.recordaudit.domain.CorpusLabel;
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

    @Lob
    @Column(nullable = false, columnDefinition = "MEDIUMTEXT")
    private String transcript;

    @Column(length = 32)
    private String violationType;

    @Lob
    @Column(columnDefinition = "TEXT")
    private String reason;

    @Column(length = 32)
    private String source = "REVIEW";

    /** 来源录音，可为空（人工录入） */
    private Long recordingId;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}