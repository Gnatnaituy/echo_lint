package com.echolint.entity;

import com.echolint.domain.RecordingStatus;
import com.echolint.domain.ReviewResult;
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
        @Index(name = "idx_recording_upload_time", columnList = "uploadTime")
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

    /** Whisper 返回的时长（秒） */
    private Integer durationSeconds;

    @Column(length = 16)
    private String language;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private RecordingStatus status = RecordingStatus.PENDING;

    /** Whisper 完整转写文本 */
    @Lob
    @Column(columnDefinition = "MEDIUMTEXT")
    private String transcript;

    /** 转写分段 JSON [{speaker,channel,start,end,text},...]；双声道时带说话人/声道 */
    @Lob
    @Column(columnDefinition = "MEDIUMTEXT")
    private String segmentsJson;

    /** 声道数（1=单声道，2=双声道双轨） */
    private Integer channelCount;

    /** 分轨文件 JSON {"L":"xxx.L.wav","R":"xxx.R.wav"}，仅双声道存在 */
    @Lob
    @Column(columnDefinition = "TEXT")
    private String channelFilesJson;

    /** DFA 命中 [{word,start,end},...] 原始 JSON */
    @Lob
    @Column(columnDefinition = "TEXT")
    private String dfaHitsJson;

    /** DFA 命中词个数 */
    private Integer hitCount = 0;

    /** AI 复筛结果 {violation,violationType,violationTypeLabel,reason,confidence,targetSentence} JSON */
    @Lob
    @Column(columnDefinition = "TEXT")
    private String aiResultJson;

    @Enumerated(EnumType.STRING)
    @Column(length = 64)
    private ReviewResult reviewResult;

    @Column(length = 64)
    private String violationType;

    @Column(length = 64)
    private String violationTypeLabel;

    @Lob
    @Column(columnDefinition = "TEXT")
    private String reviewComment;

    @Column(length = 64)
    private String reviewer;

    private LocalDateTime reviewTime;

    @Column(nullable = false)
    private LocalDateTime uploadTime;

    private LocalDateTime processedTime;

    @Lob
    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    @PrePersist
    void prePersist() {
        if (uploadTime == null) {
            uploadTime = LocalDateTime.now();
        }
    }
}