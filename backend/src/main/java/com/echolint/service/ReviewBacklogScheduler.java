package com.echolint.service;

import com.echolint.config.NotifyProperties;
import com.echolint.domain.NotifyEvent;
import com.echolint.domain.RecordingStatus;
import com.echolint.repository.RecordingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 待复核积压检查（Batch 2b-2）：每天 09:00（Asia/Shanghai）统计 NEEDS_REVIEW 数量，
 * 达到 {@code app.notify.backlog-threshold} 时发 REVIEW_BACKLOG Webhook。
 *
 * <p>检查本身失败（数据库/通知异常）只记 warn，不能影响定时任务线程。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReviewBacklogScheduler {

    private final RecordingRepository recordingRepository;
    private final NotifyProperties notifyProperties;
    private final NotificationService notificationService;

    /** 每天 09:00（Asia/Shanghai）检查待复核积压 */
    @Scheduled(cron = "0 0 9 * * *", zone = "Asia/Shanghai")
    public void checkReviewBacklog() {
        if (!notifyProperties.isEnabled()) {
            log.debug("通知未启用（app.notify.enabled=false），跳过待复核积压检查");
            return;
        }
        try {
            long count = recordingRepository.countByStatus(RecordingStatus.NEEDS_REVIEW);
            int threshold = notifyProperties.effectiveBacklogThreshold();
            if (count < threshold) {
                log.debug("待复核积压 {} 条，未达阈值 {} 条，不发送告警", count, threshold);
                return;
            }
            log.warn("待复核积压 {} 条，已达阈值 {} 条，发送 REVIEW_BACKLOG 通知", count, threshold);
            notificationService.notify(NotifyEvent.REVIEW_BACKLOG, null, "待复核积压提醒",
                    "当前待复核录音 " + count + " 条，已达到配置阈值 " + threshold + " 条，请及时安排复核",
                    null);
        } catch (Exception e) {
            log.warn("待复核积压检查失败（不影响主流程）：{}", e.getMessage());
        }
    }
}
