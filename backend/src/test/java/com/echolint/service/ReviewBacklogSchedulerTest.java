package com.echolint.service;

import com.echolint.config.NotifyProperties;
import com.echolint.domain.NotifyEvent;
import com.echolint.domain.RecordingStatus;
import com.echolint.repository.RecordingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 每日 09:00 待复核积压检查（Batch 2b-2）：达到阈值才告警，关闭跳过，异常不影响定时线程。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReviewBacklogSchedulerTest {

    @Mock
    private RecordingRepository recordingRepository;
    @Mock
    private NotificationService notificationService;

    private NotifyProperties notifyProperties;
    private ReviewBacklogScheduler scheduler;

    @BeforeEach
    void setUp() {
        notifyProperties = new NotifyProperties();
        notifyProperties.setEnabled(true);
        notifyProperties.setBacklogThreshold(50);
        scheduler = new ReviewBacklogScheduler(recordingRepository, notifyProperties, notificationService);
    }

    @Test
    void backlogAtThresholdSendsNotification() {
        when(recordingRepository.countByStatus(RecordingStatus.NEEDS_REVIEW)).thenReturn(50L);

        scheduler.checkReviewBacklog();

        verify(notificationService).notify(eq(NotifyEvent.REVIEW_BACKLOG), isNull(),
                contains("积压"), contains("50"), isNull());
    }

    @Test
    void backlogBelowThresholdStaysQuiet() {
        when(recordingRepository.countByStatus(RecordingStatus.NEEDS_REVIEW)).thenReturn(49L);

        scheduler.checkReviewBacklog();

        verify(notificationService, never()).notify(any(), any(), anyString(), anyString(), any());
    }

    @Test
    void disabledNotificationsSkipCountQueryEntirely() {
        notifyProperties.setEnabled(false);

        scheduler.checkReviewBacklog();

        verify(recordingRepository, never()).countByStatus(any());
        verify(notificationService, never()).notify(any(), any(), anyString(), anyString(), any());
    }

    @Test
    void repositoryFailureIsSwallowed() {
        when(recordingRepository.countByStatus(RecordingStatus.NEEDS_REVIEW))
                .thenThrow(new RuntimeException("db down"));

        assertDoesNotThrow(() -> scheduler.checkReviewBacklog());
        verify(notificationService, never()).notify(any(), any(), anyString(), anyString(), any());
    }
}
