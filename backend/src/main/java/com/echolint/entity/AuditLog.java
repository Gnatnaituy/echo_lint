package com.echolint.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 操作审计：谁在什么时间、从哪个 IP 对什么对象做了什么。
 *
 * <p>只读接口（ADMIN），写入失败不得影响主流程，见 AuditService。</p>
 */
@Entity
@Table(name = "audit_logs", indexes = {
        @Index(name = "idx_audit_created", columnList = "createdAt"),
        @Index(name = "idx_audit_actor", columnList = "actor"),
        @Index(name = "idx_audit_action", columnList = "action")
})
@Getter
@Setter
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 操作人用户名；系统动作为 system */
    @Column(nullable = false, length = 64)
    private String actor;

    /** 动作编码，见 {@link com.echolint.domain.AuditAction} */
    @Column(nullable = false, length = 32)
    private String action;

    @Column(length = 32)
    private String targetType;

    /** 目标 id 以字符串存，兼容录音/用户/词条等不同类型 */
    @Column(length = 64)
    private String targetId;

    @Column(columnDefinition = "TEXT")
    private String detail;

    @Column(length = 64)
    private String ip;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
