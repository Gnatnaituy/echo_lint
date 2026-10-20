package com.echolint.entity;

import com.echolint.domain.ReviewResult;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 复核记录：每一次人工复检提交都留一条，双人复核时保留完整的审批轨迹。
 *
 * <p>录音被重开时只清空录音上的最终结论字段，本表历史保留。</p>
 */
@Entity
@Table(name = "review_records", indexes = {
        @Index(name = "idx_review_recording", columnList = "recordingId"),
        @Index(name = "idx_review_created", columnList = "createdAt")
})
@Getter
@Setter
public class ReviewRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long recordingId;

    /** 复核人用户 id；历史遗留/系统数据可能为空 */
    private Long reviewerId;

    @Column(nullable = false, length = 64)
    private String reviewerUsername;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ReviewResult result;

    @Column(length = 32)
    private String violationType;

    @Column(columnDefinition = "TEXT")
    private String comment;

    /** 第几轮复核，从 1 开始 */
    @Column(nullable = false)
    private int round;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
