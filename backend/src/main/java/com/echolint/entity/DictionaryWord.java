package com.echolint.entity;

import com.echolint.domain.WordSeverity;
import com.echolint.domain.WordSource;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 敏感词词典（DFA 初筛词源）
 */
@Entity
@Table(name = "dictionary_words", indexes = {
        @Index(name = "idx_dict_enabled", columnList = "enabled")
})
@Getter
@Setter
public class DictionaryWord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 规范化后的小写词（仅保留字母数字/中日韩字符） */
    @Column(nullable = false, unique = true, length = 128)
    private String word;

    @Column(length = 32)
    private String category;

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private WordSeverity severity = WordSeverity.MEDIUM;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private WordSource source = WordSource.MANUAL;

    @Column(nullable = false)
    private Boolean enabled = Boolean.TRUE;

    /** 累计命中次数 */
    @Column(nullable = false)
    private Integer hitCount = 0;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        updatedAt = createdAt;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}