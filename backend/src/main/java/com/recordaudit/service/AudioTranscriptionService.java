package com.recordaudit.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recordaudit.config.AppProperties;
import com.recordaudit.domain.TranscriptSegment;
import com.recordaudit.exception.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 转写编排：
 * - 单声道：一次 Whisper 调用；
 * - 双声道（双轨录音）：ffmpeg 分离左右声道 → 两路分别 Whisper 转写 → 按时间对齐合并为对话分段。
 *
 * 全文 transcript 由分段文本按顺序用空格拼接，保证前端「分段 ↔ 全文偏移」精确对应。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AudioTranscriptionService {

    private final AppProperties props;
    private final AudioChannelService audioChannelService;
    private final TranscriptionService transcriptionService;
    private final ObjectMapper objectMapper;

    public record TranscriptionOutcome(
            String transcript,
            String segmentsJson,
            int channelCount,
            String channelFilesJson,
            String language,
            int durationSeconds,
            boolean stereo) {
    }

    public TranscriptionOutcome transcribe(Path audioFile) {
        AppProperties.Stereo stereo = props.getStereo();
        if (stereo.isEnabled()) {
            Integer channels = audioChannelService.detectChannels(audioFile);
            if (channels != null && channels >= 2) {
                Optional<TranscriptionOutcome> dual = tryStereo(audioFile, stereo);
                if (dual.isPresent()) {
                    return dual.get();
                }
                log.warn("双声道分离不可用，降级为单路转写: {}", audioFile.getFileName());
            }
        }
        return single(audioFile);
    }

    private TranscriptionOutcome single(Path audioFile) {
        TranscriptionService.ChannelTranscript t = transcriptionService.transcribe(audioFile);
        List<TranscriptSegment> segments = t.segments();
        String transcript = joinTranscript(segments, t.text());
        log.info("单路转写完成：分段 {} 段", segments.size());
        return new TranscriptionOutcome(transcript, toJson(segments), 1, null,
                t.language(), t.durationSeconds(), false);
    }

    private Optional<TranscriptionOutcome> tryStereo(Path audioFile, AppProperties.Stereo stereo) {
        Optional<AudioChannelService.ChannelSplit> split = audioChannelService.splitStereo(audioFile);
        if (split.isEmpty()) {
            return Optional.empty();
        }
        AudioChannelService.ChannelSplit files = split.get();
        try {
            TranscriptionService.ChannelTranscript left =
                    transcriptionService.transcribe(files.left());
            TranscriptionService.ChannelTranscript right =
                    transcriptionService.transcribe(files.right());

            List<TranscriptSegment> merged = merge(
                    tag(left.segments(), stereo.getLeftSpeaker(), "L"),
                    tag(right.segments(), stereo.getRightSpeaker(), "R"));

            if (merged.isEmpty()) {
                throw new BizException("双声道转写未识别到有效语音内容");
            }

            String transcript = joinTranscript(merged, null);
            String channelFilesJson = toJson(Map.of(
                    "L", files.left().getFileName().toString(),
                    "R", files.right().getFileName().toString()));
            int duration = Math.max(left.durationSeconds(), right.durationSeconds());
            String language = left.language() != null && !left.language().isBlank()
                    ? left.language() : right.language();

            log.info("双声道转写完成：左 {} 段 / 右 {} 段 → 合并 {} 段，时长 {}s",
                    left.segments().size(), right.segments().size(), merged.size(), duration);
            return Optional.of(new TranscriptionOutcome(transcript, toJson(merged), 2, channelFilesJson,
                    language, duration, true));
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.warn("双声道转写失败，降级单路: {}", e.getMessage());
            cleanup(files);
            return Optional.empty();
        }
    }

    /** 按时间对齐合并两路分段（同一起点时左声道在前，稳定排序） */
    public static List<TranscriptSegment> merge(List<TranscriptSegment> left, List<TranscriptSegment> right) {
        List<TranscriptSegment> all = new ArrayList<>();
        if (left != null) {
            all.addAll(left);
        }
        if (right != null) {
            all.addAll(right);
        }
        all.removeIf(TranscriptSegment::isBlank);
        all.sort(Comparator.comparingDouble(TranscriptSegment::start));
        return all;
    }

    private List<TranscriptSegment> tag(List<TranscriptSegment> segments, String speaker, String channel) {
        List<TranscriptSegment> tagged = new ArrayList<>();
        for (TranscriptSegment s : segments) {
            if (s.isBlank()) {
                continue;
            }
            tagged.add(TranscriptSegment.of(speaker, channel, s.start(), s.end(), s.text()));
        }
        return tagged;
    }

    /** 分段文本按顺序空格拼接为全文；无分段时退回 Whisper 原文 */
    static String joinTranscript(List<TranscriptSegment> segments, String fallback) {
        if (segments == null || segments.isEmpty()) {
            return fallback == null ? "" : fallback.trim();
        }
        return segments.stream()
                .map(TranscriptSegment::text)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.joining(" "));
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("序列化失败: {}", e.getMessage());
            return "[]";
        }
    }

    /** 双声道失败时清理已生成的分轨文件 */
    private void cleanup(AudioChannelService.ChannelSplit files) {
        for (Path p : List.of(files.left(), files.right())) {
            try {
                Files.deleteIfExists(p);
            } catch (Exception ignored) {
                // 忽略清理失败
            }
        }
    }

    /** 供前端/调试使用的说话人映射（声道 → 名称） */
    public Map<String, String> speakerMapping() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("L", props.getStereo().getLeftSpeaker());
        map.put("R", props.getStereo().getRightSpeaker());
        return map;
    }
}
