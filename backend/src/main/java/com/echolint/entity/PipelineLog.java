package com.echolint.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 流水线日志（一次处理的各阶段轨迹）
 */
@Entity
@Table(name = "pipeline_logs", indexes = {
        @Index(name = "idx_log_recording", columnList = "recordingId"),
        @Index(name = "idx_log_created", columnList = "createdAt")
})
@Getter
@Setter
public class PipelineLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long recordingId;

    @Column(length = 32)
    private String stage;

    @Column(length = 16)
    private String level = "INFO";

    @Lob
    @Column(columnDefinition = "TEXT")
    private String message;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}