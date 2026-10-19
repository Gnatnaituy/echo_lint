package com.echolint.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.echolint.domain.AuditAction;
import com.echolint.domain.CallDirection;
import com.echolint.domain.RecordingStatus;
import com.echolint.domain.ReviewResult;
import com.echolint.dto.RecordingFilter;
import com.echolint.entity.Recording;
import com.echolint.entity.ReviewRecord;
import com.echolint.repository.RecordingRepository;
import com.echolint.repository.ReviewRecordRepository;
import com.echolint.util.CsvWriter;
import com.echolint.util.TimeRanges;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 报表导出的 CSV 内容：BOM、表头、转义、行数上限、复核记录批量关联、审计。
 */
@ExtendWith(MockitoExtension.class)
class ReportServiceTest {

    @Mock
    private RecordingRepository recordingRepository;
    @Mock
    private ReviewRecordRepository reviewRecordRepository;
    @Mock
    private AuditService auditService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ReportService service;

    @BeforeEach
    void setUp() {
        service = new ReportService(recordingRepository, reviewRecordRepository, objectMapper, auditService);
    }

    private Recording recording(long id) {
        Recording r = new Recording();
        r.setId(id);
        r.setFileName("call, \"urgent\".mp3");
        r.setAgentName("张三");
        r.setTeamName(null);
        r.setCallTime(LocalDateTime.of(2026, 10, 20, 9, 30));
        r.setDirection(CallDirection.INBOUND);
        r.setBusinessLine("售后");
        r.setStatus(RecordingStatus.VIOLATION_CONFIRMED);
        r.setHitCount(3);
        r.setViolationType("INSULT");
        r.setViolationTypeLabel("辱骂");
        r.setAiResultJson("{\"violation\":true,\"violationType\":\"INSULT\","
                + "\"violationTypeLabel\":\"辱骂\",\"confidence\":0.91}");
        r.setReviewer("bob");
        r.setReviewTime(LocalDateTime.of(2026, 10, 20, 10, 0));
        r.setReviewComment("客户先辱骂,坐席回怼");
        r.setReviewCount(1);
        r.setUploadTime(LocalDateTime.of(2026, 10, 20, 9, 40));
        r.setDurationSeconds(30);
        r.setSampled(true);
        r.setErrorMessage("bad\nline");
        return r;
    }

    private String decode(byte[] bytes) {
        assertArrayEquals(CsvWriter.UTF8_BOM,
                java.util.Arrays.copyOf(bytes, CsvWriter.UTF8_BOM.length), "CSV 应带 UTF-8 BOM");
        return new String(bytes, CsvWriter.UTF8_BOM.length,
                bytes.length - CsvWriter.UTF8_BOM.length, StandardCharsets.UTF_8);
    }

    @Test
    void recordingsCsvHasHeaderEscapingAiColumnsAndAudit() {
        when(recordingRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(recording(1L))));

        byte[] body = service.recordingsCsv(
                RecordingFilter.byRange("2026-10-01", "2026-10-20"));

        String text = decode(body);
        String[] lines = text.split("\r\n");
        assertEquals("id,fileName,agentName,teamName,callTime,direction,businessLine,status,sampled,hitCount,"
                + "violationType,violationTypeLabel,aiViolation,aiConfidence,reviewer,reviewTime,uploadTime,"
                + "durationSeconds,errorMessage", lines[0]);
        assertEquals(2, lines.length);

        String row = lines[1];
        assertTrue(row.startsWith("1,\"call, \"\"urgent\"\".mp3\",张三,"), row);
        assertTrue(row.contains(",INBOUND,售后,"), row);
        assertTrue(row.contains(",VIOLATION_CONFIRMED,true,3,INSULT,辱骂,true,0.91,bob,"), row);
        assertTrue(row.contains("sampled") || lines[0].contains("sampled"), "recordings.csv 应包含 sampled 列");
        assertTrue(row.contains("\"bad\nline\""), row);

        verify(auditService).record(eq(AuditAction.REPORT_EXPORT), eq("REPORT"), eq("recordings.csv"),
                contains("1 行"));
    }

    @Test
    void exportIsLimitedToTenThousandRowsNewestFirst() {
        when(recordingRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        service.recordingsCsv(RecordingFilter.byRange(null, null));

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(recordingRepository).findAll(any(Specification.class), captor.capture());
        assertEquals(ReportService.MAX_EXPORT_ROWS, captor.getValue().getPageSize());
        assertEquals(0, captor.getValue().getPageNumber());
        assertEquals("id", captor.getValue().getSort().iterator().next().getProperty());
        assertEquals(org.springframework.data.domain.Sort.Direction.DESC,
                captor.getValue().getSort().iterator().next().getDirection());
    }

    @Test
    void violationsCsvForcesConfirmedStatusAndIncludesReviewMetadata() {
        when(recordingRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(recording(2L))));

        byte[] body = service.violationsCsv(RecordingFilter.byRange(null, null));

        String text = decode(body);
        String[] lines = text.split("\r\n");
        assertEquals("id,fileName,agentName,teamName,callTime,direction,businessLine,violationType,"
                + "violationTypeLabel,reviewer,reviewTime,reviewComment,reviewCount,aiViolation,"
                + "aiConfidence,hitCount,uploadTime", lines[0]);
        assertTrue(lines[1].contains("2,"));
        assertTrue(lines[1].contains("INSULT,辱骂,bob,"));
        assertTrue(lines[1].contains("\"客户先辱骂,坐席回怼\""));
        verify(auditService).record(eq(AuditAction.REPORT_EXPORT), eq("REPORT"), eq("violations.csv"),
                contains("1 行"));
    }

    @Test
    void violationsFilterKeepsOtherConditionsButForcesConfirmedStatus() {
        RecordingFilter forced = service.violationsFilter(RecordingFilter.of(null, "退款", null,
                null, "张三", "客服一组", "outbound", "2026-10-01", "2026-10-02"));

        assertEquals(List.of(RecordingStatus.VIOLATION_CONFIRMED), forced.statuses());
        assertEquals("退款", forced.keyword());
        assertEquals("张三", forced.agentName());
        assertEquals("客服一组", forced.teamName());
        assertEquals(CallDirection.OUTBOUND, forced.direction());
        assertEquals(LocalDateTime.of(2026, 10, 3, 0, 0), forced.toExclusive());
    }

    private ReviewRecord review(long id, Long recordingId) {
        ReviewRecord r = new ReviewRecord();
        r.setId(id);
        r.setRecordingId(recordingId);
        r.setReviewerUsername("bob");
        r.setResult(ReviewResult.CONFIRMED_VIOLATION);
        r.setViolationType("INSULT");
        r.setComment("备注,含逗号");
        r.setRound(2);
        r.setCreatedAt(LocalDateTime.of(2026, 10, 20, 11, 0));
        return r;
    }

    @Test
    void reviewsCsvJoinsRecordingMetadataWithSingleBatchQuery() {
        when(reviewRecordRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(review(11L, 5L))));
        when(recordingRepository.findAllById(List.of(5L))).thenReturn(List.of(recording(5L)));

        byte[] body = service.reviewsCsv(TimeRanges.parseRange("2026-10-01", "2026-10-31"));

        String text = decode(body);
        String[] lines = text.split("\r\n");
        assertEquals("reviewId,recordingId,fileName,agentName,teamName,callTime,direction,businessLine,"
                + "round,reviewer,result,violationType,comment,createdAt", lines[0]);
        assertTrue(lines[1].startsWith("11,5,\"call, \"\"urgent\"\".mp3\",张三,"), lines[1]);
        assertTrue(lines[1].contains(",2,bob,CONFIRMED_VIOLATION,INSULT,\"备注,含逗号\","));

        verify(recordingRepository).findAllById(List.of(5L));
        verify(recordingRepository, never()).findById(anyLong());
        verify(auditService).record(eq(AuditAction.REPORT_EXPORT), eq("REPORT"), eq("reviews.csv"),
                contains("1 行"));
    }

    @Test
    void reviewsCsvToleratesDeletedRecording() {
        when(reviewRecordRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(review(11L, 5L))));
        when(recordingRepository.findAllById(List.of(5L))).thenReturn(List.of());

        String text = decode(service.reviewsCsv(TimeRanges.parseRange(null, null)));

        String[] lines = text.split("\r\n");
        assertTrue(lines[1].startsWith("11,5,,,,,UNKNOWN,"), lines[1]);
    }

    @Test
    void reviewsCsvWithNoRowsOnlyWritesHeader() {
        when(reviewRecordRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        String text = decode(service.reviewsCsv(TimeRanges.parseRange(null, null)));

        assertEquals(1, text.split("\r\n").length);
        verify(recordingRepository, never()).findAllById(any());
        verify(auditService).record(eq(AuditAction.REPORT_EXPORT), eq("REPORT"), eq("reviews.csv"),
                contains("0 行"));
    }
}
