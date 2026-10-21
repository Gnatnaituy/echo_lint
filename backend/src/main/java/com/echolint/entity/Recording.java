package com.echolint.entity;

import com.echolint.domain.CallDirection;
import com.echolint.domain.RecordingStatus;
import com.echolint.domain.ReviewPriority;
import com.echolint.domain.ReviewResult;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 录音稽核主表
 */
@Entity
@Table(name = "recordings", indexes = {
        @Index(name = "idx_recording_status", columnList = "status"),
        @Index(name = "idx_recording_upload_time", columnList = "uploadTime"),
        @Index(name = "idx_recording_call_time", columnList = "callTime"),
        @Index(name = "idx_recording_agent", columnList = "agentName"),
        @Index(name = "idx_recording_team", columnList = "teamName"),
        // 复核队列/列表高频组合条件（Batch 2b-1）
        @Index(name = "idx_recording_status_id", columnList = "status,id"),
        @Index(name = "idx_recording_agent_call_time", columnList = "agentName,callTime"),
        @Index(name = "idx_recording_team_call_time", columnList = "teamName,callTime"),
        @Index(name = "idx_recording_assigned_status", columnList = "assignedTo,status")
})
@Getter
@Setter
public class Recording {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 255)
    private String fileName;

    /** 相对上传目录的存储路径 */
    @Column(nullable = false, length = 255)
    private String filePath;

    @Column(nullable = false)
    private Long fileSize;

    @Column(length = 64)
    private String mimeType;

    /**
     * 音频内容的 SHA-256（64 位小写 hex），用于上传去重。
     * 历史数据为 null（唯一索引允许多个 NULL）；仅内部使用，不通过 API 返回。
     */
    @JsonIgnore
    @Column(length = 64, unique = true)
    private String audioSha256;

    /** Whisper 返回的时长（秒） */
    private Integer durationSeconds;

    @Column(length = 16)
    private String language;

    /** 坐席名称（可选，人工填写/导入） */
    @Column(length = 64)
    private String agentName;

    /** 团队名称（可选，人工填写/导入） */
    @Column(length = 128)
    private String teamName;

    /** 通话时间（可选；为空时统计/筛选回退 uploadTime） */
    private LocalDateTime callTime;

    /** 通话方向，历史数据为 null 时按 UNKNOWN 处理 */
    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private CallDirection direction = CallDirection.UNKNOWN;

    /** 业务线（可选，人工填写/导入） */
    @Column(length = 64)
    private String businessLine;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private RecordingStatus status = RecordingStatus.PENDING;

    /** Whisper 完整转写文本 */
    @Column(columnDefinition = "TEXT")
    private String transcript;

    /** 转写分段 JSON [{speaker,channel,start,end,text},...]；双声道时带说话人/声道 */
    @Column(columnDefinition = "TEXT")
    private String segmentsJson;

    /** 声道数（1=单声道，2=双声道双轨） */
    private Integer channelCount;

    /** 分轨文件 JSON {"L":"xxx.L.wav","R":"xxx.R.wav"}，仅双声道存在 */
    @Column(columnDefinition = "TEXT")
    private String channelFilesJson;

    /** DFA 命中 [{word,start,end},...] 原始 JSON */
    @Column(columnDefinition = "TEXT")
    private String dfaHitsJson;

    /** DFA 命中词个数 */
    private Integer hitCount = 0;

    /**
     * 是否为「DFA 未命中后的抽样复核」录音（Batch 2b-2，app.sample.dfa-miss-rate）。
     * 历史数据为 false；抽样录音在列表/详情/CSV 中显式标记，便于回溯抽检覆盖率。
     */
    @Column(nullable = false)
    private boolean sampled = false;

    /** AI 复筛结果 {violation,violationType,violationTypeLabel,reason,confidence,targetSentence} JSON */
    @Column(columnDefinition = "TEXT")
    private String aiResultJson;

    @Enumerated(EnumType.STRING)
    @Column(length = 64)
    private ReviewResult reviewResult;

    @Column(length = 64)
    private String violationType;

    @Column(length = 64)
    private String violationTypeLabel;

    @Column(columnDefinition = "TEXT")
    private String reviewComment;

    @Column(length = 64)
    private String reviewer;

    private LocalDateTime reviewTime;

    @Column(nullable = false)
    private LocalDateTime uploadTime;

    private LocalDateTime processedTime;

    /** 最近一次状态变更时间（用于判断处理是否卡住） */
    private LocalDateTime statusUpdatedAt;

    /** 指派的复核人用户名（可空；认领/管理员指派后写入） */
    @Column(length = 64)
    private String assignedTo;

    /**
     * 复核优先级 LOW / NORMAL / HIGH。
     * 历史数据可能为 null，列表展示时按 NORMAL 处理。
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private ReviewPriority priority = ReviewPriority.NORMAL;

    /** 已完成的复核轮次（达到 app.review.required-approvals 后落最终结论） */
    @Column(nullable = false)
    private int reviewCount = 0;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    @PrePersist
    void prePersist() {
        if (uploadTime == null) {
            uploadTime = LocalDateTime.now();
        }
        if (priority == null) {
            priority = ReviewPriority.NORMAL;
        }
        statusUpdatedAt = LocalDateTime.now();
    }

    @PreUpdate
    void preUpdate() {
        statusUpdatedAt = LocalDateTime.now();
    }
}