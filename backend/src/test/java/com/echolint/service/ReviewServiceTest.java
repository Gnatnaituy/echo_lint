package com.echolint.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.echolint.config.AppProperties;
import com.echolint.domain.AuditAction;
import com.echolint.domain.CorpusLabel;
import com.echolint.domain.NotifyEvent;
import com.echolint.domain.RecordingStatus;
import com.echolint.domain.ReviewResult;
import com.echolint.domain.UserRole;
import com.echolint.domain.ViolationType;
import com.echolint.dto.ReviewRequest;
import com.echolint.entity.Recording;
import com.echolint.entity.ReviewRecord;
import com.echolint.exception.BizException;
import com.echolint.repository.PipelineLogRepository;
import com.echolint.repository.RecordingRepository;
import com.echolint.repository.ReviewRecordRepository;
import com.echolint.security.AppUserDetails;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 复核工作流：单人复核落结论、双人复核未达标保持待复检、同一人不能连续复核、状态守卫、历史。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReviewServiceTest {

    @Mock
    private RecordingRepository recordingRepository;
    @Mock
    private PipelineLogRepository pipelineLogRepository;
    @Mock
    private CorpusService corpusService;
    @Mock
    private WordMiningService wordMiningService;
    @Mock
    private ReviewRecordRepository reviewRecordRepository;
    @Mock
    private AuditService auditService;
    @Mock
    private NotificationService notificationService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private AppProperties appProperties;
    private ReviewService service;

    @BeforeEach
    void setUp() {
        appProperties = new AppProperties();
        service = new ReviewService(recordingRepository, pipelineLogRepository, corpusService,
                new AsyncWordMiningTrigger(wordMiningService), objectMapper, reviewRecordRepository,
                auditService, appProperties, notificationService);
        when(recordingRepository.save(any(Recording.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void loginAs(String username, Long id) {
        AppUserDetails details = new AppUserDetails(id, username, "hash", username, UserRole.REVIEWER, true);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities()));
    }

    private Recording needsReview() {
        Recording recording = new Recording();
        recording.setId(7L);
        recording.setFileName("call.mp3");
        recording.setFilePath("stored.mp3");
        recording.setFileSize(100L);
        recording.setStatus(RecordingStatus.NEEDS_REVIEW);
        recording.setReviewCount(0);
        recording.setTranscript("you idiot");
        recording.setAiResultJson("{\"reason\":\"AI 判定辱骂\"}");
        return recording;
    }

    private ReviewRecord previousReview(String reviewer, int round) {
        ReviewRecord record = new ReviewRecord();
        record.setRecordingId(7L);
        record.setReviewerUsername(reviewer);
        record.setReviewerId(99L);
        record.setResult(ReviewResult.CONFIRMED_VIOLATION);
        record.setRound(round);
        return record;
    }

    private ReviewRecord capturedReviewRecord() {
        ArgumentCaptor<ReviewRecord> captor = ArgumentCaptor.forClass(ReviewRecord.class);
        verify(reviewRecordRepository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void singleApprovalFinalizesAndFeedsCorpusAndMining() {
        Recording recording = needsReview();
        when(recordingRepository.findById(7L)).thenReturn(Optional.of(recording));
        loginAs("alice", 5L);

        Recording saved = service.review(7L, new ReviewRequest("CONFIRMED_VIOLATION", "INSULT", "确认辱骂"));

        assertEquals(RecordingStatus.VIOLATION_CONFIRMED, saved.getStatus());
        assertEquals("INSULT", saved.getViolationType());
        assertEquals("辱骂", saved.getViolationTypeLabel());
        assertEquals(ReviewResult.CONFIRMED_VIOLATION, saved.getReviewResult());
        assertEquals(1, saved.getReviewCount());
        assertEquals("alice", saved.getReviewer());
        assertNotNull(saved.getReviewTime());

        ReviewRecord record = capturedReviewRecord();
        assertEquals(7L, record.getRecordingId());
        assertEquals(5L, record.getReviewerId());
        assertEquals("alice", record.getReviewerUsername());
        assertEquals(ReviewResult.CONFIRMED_VIOLATION, record.getResult());
        assertEquals("INSULT", record.getViolationType());
        assertEquals(1, record.getRound());

        verify(corpusService).append(eq(recording), eq(CorpusLabel.VIOLATION), eq("INSULT"), eq("确认辱骂"));
        verify(wordMiningService).mineAndSuggest(recording, ViolationType.INSULT);
        verify(auditService).record(eq(AuditAction.REVIEW_SUBMIT), eq("RECORDING"), eq(7L), anyString());
        // Batch 2b-2：确认违规应发出 Webhook 告警
        verify(notificationService).notify(eq(NotifyEvent.REVIEW_CONFIRMED_VIOLATION), eq(7L),
                contains("辱骂"), contains("确认辱骂"), isNull());
    }

    @Test
    void confirmedViolationNotificationIsSentOnlyAfterCommit() {
        Recording recording = needsReview();
        when(recordingRepository.findById(7L)).thenReturn(Optional.of(recording));
        loginAs("alice", 5L);
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.review(7L, new ReviewRequest("CONFIRMED_VIOLATION", "INSULT", "确认辱骂"));

            // 事务未提交前不能发告警，避免「通知已发但回滚」
            verify(notificationService, never()).notify(any(), any(), anyString(), anyString(), any());
            TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
            verify(notificationService).notify(eq(NotifyEvent.REVIEW_CONFIRMED_VIOLATION), eq(7L),
                    anyString(), anyString(), isNull());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void miningRunsOnlyAfterTransactionCommit() {
        Recording recording = needsReview();
        when(recordingRepository.findById(7L)).thenReturn(Optional.of(recording));
        loginAs("alice", 5L);
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.review(7L, new ReviewRequest("CONFIRMED_VIOLATION", "INSULT", "确认辱骂"));

            // AI 词挖掘可能耗时数百秒，不允许留在复核事务内执行
            verify(wordMiningService, never()).mineAndSuggest(any(), any());
            List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
            assertFalse(synchronizations.isEmpty(), "应在当前事务注册 afterCommit 回调");

            synchronizations.forEach(TransactionSynchronization::afterCommit);

            verify(wordMiningService).mineAndSuggest(recording, ViolationType.INSULT);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void miningFailureAfterCommitDoesNotPropagate() {
        Recording recording = needsReview();
        when(recordingRepository.findById(7L)).thenReturn(Optional.of(recording));
        doThrow(new RuntimeException("AI 不可用")).when(wordMiningService).mineAndSuggest(any(), any());
        loginAs("alice", 5L);
        TransactionSynchronizationManager.initSynchronization();
        try {
            Recording saved = service.review(7L, new ReviewRequest("CONFIRMED_VIOLATION", "INSULT", "确认辱骂"));
            assertEquals(RecordingStatus.VIOLATION_CONFIRMED, saved.getStatus());

            assertDoesNotThrow(() -> TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void firstOfTwoApprovalsStaysNeedsReviewWithoutCorpusOrMining() {
        appProperties.getReview().setRequiredApprovals(2);
        Recording recording = needsReview();
        when(recordingRepository.findById(7L)).thenReturn(Optional.of(recording));
        loginAs("alice", 5L);

        Recording saved = service.review(7L, new ReviewRequest("CONFIRMED_VIOLATION", "INSULT", "第一票"));

        assertEquals(RecordingStatus.NEEDS_REVIEW, saved.getStatus());
        assertEquals(1, saved.getReviewCount());
        assertNull(saved.getReviewResult(), "未达标不应写最终结论");
        assertNull(saved.getReviewer());
        assertNull(saved.getReviewTime());
        assertNull(saved.getReviewComment());
        assertEquals(1, capturedReviewRecord().getRound());

        verify(corpusService, never()).append(any(), any(), any(), any());
        verify(wordMiningService, never()).mineAndSuggest(any(), any());
        verify(notificationService, never()).notify(any(), any(), anyString(), anyString(), any());
    }

    @Test
    void secondApprovalByDifferentReviewerFinalizes() {
        appProperties.getReview().setRequiredApprovals(2);
        Recording recording = needsReview();
        recording.setReviewCount(1);
        when(recordingRepository.findById(7L)).thenReturn(Optional.of(recording));
        when(reviewRecordRepository.findTopByRecordingIdOrderByRoundDescIdDesc(7L))
                .thenReturn(Optional.of(previousReview("bob", 1)));
        loginAs("alice", 5L);

        Recording saved = service.review(7L, new ReviewRequest("CONFIRMED_VIOLATION", "INSULT", "第二票"));

        assertEquals(RecordingStatus.VIOLATION_CONFIRMED, saved.getStatus());
        assertEquals(2, saved.getReviewCount());
        assertEquals("alice", saved.getReviewer());
        assertEquals(2, capturedReviewRecord().getRound());
        verify(corpusService).append(eq(recording), eq(CorpusLabel.VIOLATION), eq("INSULT"), eq("第二票"));
        verify(wordMiningService).mineAndSuggest(recording, ViolationType.INSULT);
        verify(notificationService).notify(eq(NotifyEvent.REVIEW_CONFIRMED_VIOLATION), eq(7L),
                anyString(), contains("第二票"), isNull());
    }

    @Test
    void sameReviewerCannotReviewConsecutivelyWhenTwoApprovalsRequired() {
        appProperties.getReview().setRequiredApprovals(2);
        Recording recording = needsReview();
        recording.setReviewCount(1);
        when(recordingRepository.findById(7L)).thenReturn(Optional.of(recording));
        when(reviewRecordRepository.findTopByRecordingIdOrderByRoundDescIdDesc(7L))
                .thenReturn(Optional.of(previousReview("alice", 1)));
        loginAs("alice", 5L);

        BizException e = assertThrows(BizException.class,
                () -> service.review(7L, new ReviewRequest("CONFIRMED_VIOLATION", "INSULT", "重复提交")));

        assertEquals(HttpStatus.CONFLICT, e.getStatus());
        assertTrue(e.getMessage().contains("不能连续复核"));
        verify(recordingRepository, never()).save(any());
        verify(corpusService, never()).append(any(), any(), any(), any());
    }

    @Test
    void recordingNotInNeedsReviewCannotBeReviewed() {
        Recording recording = needsReview();
        recording.setStatus(RecordingStatus.COMPLIANT);
        when(recordingRepository.findById(7L)).thenReturn(Optional.of(recording));
        loginAs("alice", 5L);

        BizException e = assertThrows(BizException.class,
                () -> service.review(7L, new ReviewRequest("FALSE_POSITIVE", null, null)));

        assertEquals(HttpStatus.CONFLICT, e.getStatus());
        verify(recordingRepository, never()).save(any());
    }

    @Test
    void confirmedViolationRequiresViolationType() {
        Recording recording = needsReview();
        when(recordingRepository.findById(7L)).thenReturn(Optional.of(recording));
        loginAs("alice", 5L);

        BizException e = assertThrows(BizException.class,
                () -> service.review(7L, new ReviewRequest("CONFIRMED_VIOLATION", "NONE", null)));

        assertTrue(e.getMessage().contains("违规类型"));
        verify(recordingRepository, never()).save(any());
    }

    @Test
    void falsePositiveFinalizesCompliantCorpus() {
        Recording recording = needsReview();
        when(recordingRepository.findById(7L)).thenReturn(Optional.of(recording));
        loginAs("alice", 5L);

        Recording saved = service.review(7L, new ReviewRequest("FALSE_POSITIVE", null, "误报"));

        assertEquals(RecordingStatus.FALSE_POSITIVE, saved.getStatus());
        assertEquals(ReviewResult.FALSE_POSITIVE, saved.getReviewResult());
        assertEquals("alice", saved.getReviewer());
        verify(corpusService).append(eq(recording), eq(CorpusLabel.COMPLIANT), isNull(), eq("误报"));
        verify(wordMiningService, never()).mineAndSuggest(any(), any());
        // 误报不触发告警（只有确认违规才发）
        verify(notificationService, never()).notify(any(), any(), anyString(), anyString(), any());
    }

    @Test
    void historyIsReturnedInRoundOrder() {
        Recording recording = needsReview();
        ReviewRecord first = previousReview("bob", 1);
        ReviewRecord second = previousReview("alice", 2);
        when(recordingRepository.findById(7L)).thenReturn(Optional.of(recording));
        when(reviewRecordRepository.findByRecordingIdOrderByRoundAscIdAsc(7L))
                .thenReturn(List.of(first, second));

        List<ReviewRecord> history = service.history(7L);

        assertEquals(List.of(first, second), history);
    }
}
