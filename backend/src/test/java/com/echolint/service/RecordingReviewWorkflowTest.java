package com.echolint.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.echolint.config.AppProperties;
import com.echolint.domain.AuditAction;
import com.echolint.domain.RecordingStatus;
import com.echolint.domain.ReviewResult;
import com.echolint.domain.UserRole;
import com.echolint.entity.AppUser;
import com.echolint.entity.Recording;
import com.echolint.exception.BizException;
import com.echolint.repository.AppUserRepository;
import com.echolint.repository.CorpusEntryRepository;
import com.echolint.repository.PipelineLogRepository;
import com.echolint.repository.RecordingRepository;
import com.echolint.repository.ReviewRecordRepository;
import com.echolint.security.AppUserDetails;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 复核队列操作：认领（状态/占用守卫）、管理员指派（用户校验）、重开（终态守卫 + 清空结论）。
 */
@ExtendWith(MockitoExtension.class)
class RecordingReviewWorkflowTest {

    @Mock
    private RecordingRepository recordingRepository;
    @Mock
    private PipelineLogRepository pipelineLogRepository;
    @Mock
    private AiInvocationService aiInvocationService;
    @Mock
    private PipelineService pipelineService;
    @Mock
    private AuditService auditService;
    @Mock
    private AppUserRepository userRepository;
    @Mock
    private ReviewRecordRepository reviewRecordRepository;
    @Mock
    private CorpusEntryRepository corpusEntryRepository;

    @TempDir
    Path tempDir;

    private RecordingService service;

    @BeforeEach
    void setUp() {
        AppProperties appProperties = new AppProperties();
        appProperties.setUploadDir(tempDir.toString());
        ObjectMapper objectMapper = new ObjectMapper();
        service = new RecordingService(recordingRepository, pipelineLogRepository, aiInvocationService,
                pipelineService, appProperties, objectMapper, auditService, userRepository, reviewRecordRepository,
                corpusEntryRepository, new AudioFileValidator());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void loginAs(String username) {
        AppUserDetails details = new AppUserDetails(9L, username, "hash", username, UserRole.REVIEWER, true);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities()));
    }

    private Recording recording(RecordingStatus status) {
        Recording recording = new Recording();
        recording.setId(1L);
        recording.setFileName("call.mp3");
        recording.setFilePath("stored.mp3");
        recording.setFileSize(100L);
        recording.setStatus(status);
        return recording;
    }

    private AppUser user(String username, UserRole role, boolean enabled) {
        AppUser user = new AppUser();
        user.setId(2L);
        user.setUsername(username);
        user.setPasswordHash("hash");
        user.setDisplayName(username);
        user.setRole(role);
        user.setEnabled(enabled);
        return user;
    }

    private void stubSave() {
        when(recordingRepository.save(any(Recording.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    // ---------- 认领 ----------

    @Test
    void claimSetsAssignedToCurrentUser() {
        Recording recording = recording(RecordingStatus.NEEDS_REVIEW);
        when(recordingRepository.findById(1L)).thenReturn(Optional.of(recording));
        stubSave();
        loginAs("alice");

        Recording claimed = service.claim(1L);

        assertEquals("alice", claimed.getAssignedTo());
        verify(recordingRepository).save(recording);
        verify(auditService).record(eq(AuditAction.RECORDING_CLAIM), eq("RECORDING"), eq(1L), anyString());
    }

    @Test
    void claimRejectsRecordingNotAwaitingReview() {
        Recording recording = recording(RecordingStatus.COMPLIANT);
        when(recordingRepository.findById(1L)).thenReturn(Optional.of(recording));
        loginAs("alice");

        BizException e = assertThrows(BizException.class, () -> service.claim(1L));

        assertEquals(HttpStatus.CONFLICT, e.getStatus());
        verify(recordingRepository, never()).save(any());
    }

    @Test
    void claimRejectsRecordingAlreadyClaimedByAnotherUser() {
        Recording recording = recording(RecordingStatus.NEEDS_REVIEW);
        recording.setAssignedTo("bob");
        when(recordingRepository.findById(1L)).thenReturn(Optional.of(recording));
        loginAs("alice");

        BizException e = assertThrows(BizException.class, () -> service.claim(1L));

        assertEquals(HttpStatus.CONFLICT, e.getStatus());
        assertTrue(e.getMessage().contains("bob"));
        verify(recordingRepository, never()).save(any());
    }

    @Test
    void claimBySameUserIsIdempotent() {
        Recording recording = recording(RecordingStatus.NEEDS_REVIEW);
        recording.setAssignedTo("alice");
        when(recordingRepository.findById(1L)).thenReturn(Optional.of(recording));
        stubSave();
        loginAs("alice");

        Recording claimed = service.claim(1L);

        assertEquals("alice", claimed.getAssignedTo());
    }

    // ---------- 指派 ----------

    @Test
    void adminAssignsReviewer() {
        Recording recording = recording(RecordingStatus.NEEDS_REVIEW);
        when(recordingRepository.findById(1L)).thenReturn(Optional.of(recording));
        when(userRepository.findByUsername("bob")).thenReturn(Optional.of(user("bob", UserRole.REVIEWER, true)));
        stubSave();

        Recording assigned = service.assign(1L, " BOB ");

        assertEquals("bob", assigned.getAssignedTo());
        verify(auditService).record(eq(AuditAction.RECORDING_ASSIGN), eq("RECORDING"), eq(1L), anyString());
    }

    @Test
    void assignRejectsUnknownUser() {
        Recording recording = recording(RecordingStatus.NEEDS_REVIEW);
        when(recordingRepository.findById(1L)).thenReturn(Optional.of(recording));
        when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());

        BizException e = assertThrows(BizException.class, () -> service.assign(1L, "ghost"));

        assertTrue(e.getMessage().contains("不存在"));
        verify(recordingRepository, never()).save(any());
    }

    @Test
    void assignRejectsViewerAndDisabledUsers() {
        Recording recording = recording(RecordingStatus.NEEDS_REVIEW);
        when(recordingRepository.findById(1L)).thenReturn(Optional.of(recording));
        when(userRepository.findByUsername("viewer")).thenReturn(Optional.of(user("viewer", UserRole.VIEWER, true)));
        when(userRepository.findByUsername("off")).thenReturn(Optional.of(user("off", UserRole.REVIEWER, false)));

        assertThrows(BizException.class, () -> service.assign(1L, "viewer"));
        assertThrows(BizException.class, () -> service.assign(1L, "off"));
        verify(recordingRepository, never()).save(any());
    }

    // ---------- 重开 ----------

    @Test
    void reopenClearsFinalConclusionButKeepsAssignmentAndHistory() {
        Recording recording = recording(RecordingStatus.VIOLATION_CONFIRMED);
        recording.setReviewResult(ReviewResult.CONFIRMED_VIOLATION);
        recording.setViolationType("INSULT");
        recording.setViolationTypeLabel("辱骂");
        recording.setReviewer("alice");
        recording.setReviewTime(LocalDateTime.now());
        recording.setReviewComment("确认辱骂");
        recording.setReviewCount(2);
        recording.setAssignedTo("bob");
        when(recordingRepository.findById(1L)).thenReturn(Optional.of(recording));
        stubSave();

        Recording reopened = service.reopen(1L);

        assertEquals(RecordingStatus.NEEDS_REVIEW, reopened.getStatus());
        assertNull(reopened.getReviewResult());
        assertNull(reopened.getViolationType());
        assertNull(reopened.getViolationTypeLabel());
        assertNull(reopened.getReviewer());
        assertNull(reopened.getReviewTime());
        assertNull(reopened.getReviewComment());
        assertEquals(0, reopened.getReviewCount());
        assertEquals("bob", reopened.getAssignedTo(), "重开不应清空指派");
        verify(auditService).record(eq(AuditAction.REVIEW_REOPEN), eq("RECORDING"), eq(1L), anyString());
    }

    @Test
    void reopenRejectsRecordingWithoutFinalVerdict() {
        Recording recording = recording(RecordingStatus.NEEDS_REVIEW);
        when(recordingRepository.findById(1L)).thenReturn(Optional.of(recording));

        BizException e = assertThrows(BizException.class, () -> service.reopen(1L));

        assertEquals(HttpStatus.CONFLICT, e.getStatus());
        verify(recordingRepository, never()).save(any());
    }

    @Test
    void reopenCompliantRecordingIsAllowed() {
        Recording recording = recording(RecordingStatus.COMPLIANT);
        recording.setReviewCount(1);
        recording.setAssignedTo("bob");
        when(recordingRepository.findById(1L)).thenReturn(Optional.of(recording));
        stubSave();

        Recording reopened = service.reopen(1L);

        assertEquals(RecordingStatus.NEEDS_REVIEW, reopened.getStatus());
        assertEquals(0, reopened.getReviewCount());
        assertEquals("bob", reopened.getAssignedTo());
    }
}
