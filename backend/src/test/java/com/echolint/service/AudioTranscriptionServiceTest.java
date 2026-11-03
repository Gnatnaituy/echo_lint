package com.echolint.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.echolint.config.AppProperties;
import com.echolint.domain.TranscriptSegment;
import com.echolint.exception.BizException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AudioTranscriptionServiceTest {

    private TranscriptSegment seg(
            String speaker, String channel, double start, double end, String text) {
        return TranscriptSegment.of(speaker, channel, start, end, text);
    }

    private AudioTranscriptionService stereoService(
            Path source,
            Path left,
            Path right,
            AudioChannelService channelService,
            TranscriptionService transcriptionService) {
        when(channelService.detectChannels(source)).thenReturn(2);
        when(channelService.splitStereo(source))
                .thenReturn(Optional.of(new AudioChannelService.ChannelSplit(left, right)));
        return new AudioTranscriptionService(
                new AppProperties(), channelService, transcriptionService, new ObjectMapper());
    }

    @Test
    void mergeAlignsTwoChannelsByTime() {
        List<TranscriptSegment> left =
                List.of(
                        seg("坐席", "L", 0.0, 2.0, "Thank you for calling."),
                        seg("坐席", "L", 6.0, 8.5, "I can help with that."));
        List<TranscriptSegment> right =
                List.of(
                        seg("客户", "R", 2.4, 5.8, "I want a refund."),
                        seg("客户", "R", 9.0, 10.2, "Right now."));

        List<TranscriptSegment> merged = AudioTranscriptionService.merge(left, right);

        assertEquals(4, merged.size());
        assertEquals("Thank you for calling.", merged.get(0).text());
        assertEquals("I want a refund.", merged.get(1).text());
        assertEquals("I can help with that.", merged.get(2).text());
        assertEquals("Right now.", merged.get(3).text());
        // 说话人/声道信息保留
        assertEquals("坐席", merged.get(0).speaker());
        assertEquals("L", merged.get(0).channel());
        assertEquals("客户", merged.get(1).speaker());
        assertEquals("R", merged.get(1).channel());
        // 时间单调不减
        for (int i = 1; i < merged.size(); i++) {
            assertTrue(merged.get(i).start() >= merged.get(i - 1).start(), "时间应单调不减");
        }
    }

    @Test
    void mergeDropsBlankSegmentsAndHandlesNulls() {
        List<TranscriptSegment> left =
                List.of(seg("坐席", "L", 0.0, 1.0, "   "), seg("坐席", "L", 1.0, 2.0, " hello "));
        List<TranscriptSegment> merged = AudioTranscriptionService.merge(left, null);

        assertEquals(1, merged.size());
        assertEquals("hello", merged.get(0).text());
        assertTrue(AudioTranscriptionService.merge(null, null).isEmpty());
    }

    @Test
    void mergeKeepsLeftFirstWhenSameStart() {
        List<TranscriptSegment> left = List.of(seg("坐席", "L", 3.0, 4.0, "agent"));
        List<TranscriptSegment> right = List.of(seg("客户", "R", 3.0, 4.0, "customer"));

        List<TranscriptSegment> merged = AudioTranscriptionService.merge(left, right);

        assertEquals(2, merged.size());
        assertEquals("agent", merged.get(0).text(), "同起点时左声道（坐席）在前");
        assertEquals("customer", merged.get(1).text());
    }

    @Test
    void joinTranscriptUsesSingleSpaceAndKeepsOffsetsExact() {
        List<TranscriptSegment> segments =
                List.of(
                        seg("坐席", "L", 0, 1, " Thank you. "),
                        seg("客户", "R", 1, 2, "Hi there."),
                        seg("坐席", "L", 2, 3, "   "));

        String transcript = AudioTranscriptionService.joinTranscript(segments, "fallback");

        assertEquals("Thank you. Hi there.", transcript);
        // 前端按 indexOf 顺序定位分段，拼接结果必须能逐段命中
        int cursor = 0;
        for (TranscriptSegment s : segments) {
            if (s.text().isBlank()) {
                continue;
            }
            int idx = transcript.indexOf(s.text().trim(), cursor);
            assertTrue(idx >= 0, "分段文本应能在全文按顺序定位: " + s.text());
            cursor = idx + s.text().trim().length();
        }
    }

    @Test
    void joinTranscriptFallsBackWhenNoSegments() {
        assertEquals(
                "plain text",
                AudioTranscriptionService.joinTranscript(List.of(), "  plain text  "));
        assertEquals("", AudioTranscriptionService.joinTranscript(null, null));
    }

    @Test
    void stereoAsrFailureDegradesToSingleAndCleansSplitFiles(@TempDir Path dir) throws IOException {
        Path source = Files.writeString(dir.resolve("call.wav"), "audio");
        Path left = Files.writeString(dir.resolve("call.wav.L.wav"), "left");
        Path right = Files.writeString(dir.resolve("call.wav.R.wav"), "right");
        AudioChannelService channelService = mock(AudioChannelService.class);
        TranscriptionService transcriptionService = mock(TranscriptionService.class);
        AudioTranscriptionService service =
                stereoService(source, left, right, channelService, transcriptionService);

        when(transcriptionService.transcribe(left)).thenThrow(new BizException("whisper down"));
        when(transcriptionService.transcribe(source))
                .thenReturn(
                        new TranscriptionService.ChannelTranscript(
                                "hello", "en", 8, List.of(seg(null, null, 0, 1, "hello"))));

        var outcome = service.transcribe(source);

        assertFalse(outcome.stereo(), "ASR 调用失败应降级单路，而不是让整条录音失败");
        assertEquals("hello", outcome.transcript());
        assertFalse(Files.exists(left), "降级后必须清理左声道分轨文件");
        assertFalse(Files.exists(right), "降级后必须清理右声道分轨文件");
    }

    @Test
    void stereoWithoutSpeechThrowsButCleansSplitFiles(@TempDir Path dir) throws IOException {
        Path source = Files.writeString(dir.resolve("call.wav"), "audio");
        Path left = Files.writeString(dir.resolve("call.wav.L.wav"), "left");
        Path right = Files.writeString(dir.resolve("call.wav.R.wav"), "right");
        AudioChannelService channelService = mock(AudioChannelService.class);
        TranscriptionService transcriptionService = mock(TranscriptionService.class);
        AudioTranscriptionService service =
                stereoService(source, left, right, channelService, transcriptionService);

        when(transcriptionService.transcribe(left))
                .thenReturn(new TranscriptionService.ChannelTranscript("", "en", 8, List.of()));
        when(transcriptionService.transcribe(right))
                .thenReturn(new TranscriptionService.ChannelTranscript("", "en", 8, List.of()));

        BizException e = assertThrows(BizException.class, () -> service.transcribe(source));

        assertTrue(e.getMessage().contains("双声道"));
        assertFalse(Files.exists(left), "失败路径也必须清理左声道分轨文件");
        assertFalse(Files.exists(right), "失败路径也必须清理右声道分轨文件");
    }
}
