package com.echolint.service;

import com.echolint.config.AppProperties;
import com.echolint.domain.TranscriptSegment;
import com.echolint.exception.BizException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 转写编排： - 单声道：一次 Whisper 调用； - 双声道（双轨录音）：ffmpeg 分离左右声道 → 两路分别 Whisper 转写 → 按时间对齐合并为对话分段。
 *
 * <p>全文 transcript 由分段文本按顺序用空格拼接，保证前端「分段 ↔ 全文偏移」精确对应。
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
            boolean stereo) {}

    public TranscriptionOutcome transcribe(Path audioFile) {
        AppProperties.Stereo stereo = props.getStereo();
        if (stereo.isEnabled()) {
            Integer channels = audioChannelService.detectChannels(audioFile);
            if (channels != null && channels >= 2) {
                Optional<TranscriptionOutcome> dual = tryStereo(audioFile, stereo);
                if (dual.isPresent()) {
                    return dual.get();
                }
                // 多声道源文件：ASR 通常只取 0 声道，直接单路转写会静默丢掉另一侧对话（漏审风险）。
                // 降级前先 ffmpeg downmix 成 mono；downmix 不可用时才退回源文件。
                log.warn("多声道降级为单声道转写：双声道分离或任一路转写失败（{}）", audioFile.getFileName());
                return singleDownmixed(audioFile);
            }
        }
        return single(audioFile);
    }

    /** 多声道降级单路：先下混成 mono 再转写（避免只识别 0 声道丢话）；临时文件用后即删。 */
    private TranscriptionOutcome singleDownmixed(Path audioFile) {
        Optional<Path> mono = audioChannelService.downmixToMono(audioFile);
        if (mono.isEmpty()) {
            log.warn("多声道降级为单声道转写失败（downmix 不可用），直接转写源文件（可能只覆盖 0 声道）: {}", audioFile.getFileName());
            return single(audioFile);
        }
        Path monoFile = mono.get();
        try {
            return single(monoFile);
        } finally {
            try {
                Files.deleteIfExists(monoFile);
            } catch (Exception e) {
                log.warn("清理下混 mono 文件失败 {}: {}", monoFile.getFileName(), e.getMessage());
            }
        }
    }

    private TranscriptionOutcome single(Path audioFile) {
        TranscriptionService.ChannelTranscript t = transcriptionService.transcribe(audioFile);
        List<TranscriptSegment> segments = t.segments();
        String transcript = joinTranscript(segments, t.text());
        log.info("单路转写完成：分段 {} 段", segments.size());
        return new TranscriptionOutcome(
                transcript, toJson(segments), 1, null, t.language(), t.durationSeconds(), false);
    }

    /**
     * 尝试双声道转写：成功返回结果；ASR 调用失败时返回 empty 表示「降级单路」； 「两路都没有有效语音」属于确定性失败，抛 {@link BizException} 让整条录音失败。
     *
     * <p>只要没有成功返回（降级/抛错），finally 都会清理 {@code .L.wav/.R.wav} 分轨文件， 避免失败录音在 uploads
     * 卷留下与源文件同体积的孤儿文件（审计 P2-1）。
     */
    private Optional<TranscriptionOutcome> tryStereo(Path audioFile, AppProperties.Stereo stereo) {
        Optional<AudioChannelService.ChannelSplit> split =
                audioChannelService.splitStereo(audioFile);
        if (split.isEmpty()) {
            return Optional.empty();
        }
        AudioChannelService.ChannelSplit files = split.get();
        boolean success = false;
        try {
            TranscriptionService.ChannelTranscript left;
            TranscriptionService.ChannelTranscript right;
            try {
                left = transcriptionService.transcribe(files.left());
                right = transcriptionService.transcribe(files.right());
            } catch (Exception e) {
                // ASR 调用失败（网络/whisper 异常/返回格式错误）：降级单路转写，
                // 不让整条录音失败（finally 会清理已经生成的分轨文件）
                log.warn("双声道 ASR 调用失败，降级单路转写: {}", e.getMessage());
                return Optional.empty();
            }

            List<TranscriptSegment> merged =
                    merge(
                            tag(left.segments(), stereo.getLeftSpeaker(), "L"),
                            tag(right.segments(), stereo.getRightSpeaker(), "R"));

            if (merged.isEmpty()) {
                throw new BizException("双声道转写未识别到有效语音内容");
            }

            String transcript = joinTranscript(merged, null);
            String channelFilesJson =
                    toJson(
                            Map.of(
                                    "L", files.left().getFileName().toString(),
                                    "R", files.right().getFileName().toString()));
            int duration = Math.max(left.durationSeconds(), right.durationSeconds());
            String language =
                    left.language() != null && !left.language().isBlank()
                            ? left.language()
                            : right.language();

            log.info(
                    "双声道转写完成：左 {} 段 / 右 {} 段 → 合并 {} 段，时长 {}s",
                    left.segments().size(),
                    right.segments().size(),
                    merged.size(),
                    duration);
            success = true;
            return Optional.of(
                    new TranscriptionOutcome(
                            transcript,
                            toJson(merged),
                            2,
                            channelFilesJson,
                            language,
                            duration,
                            true));
        } finally {
            if (!success) {
                cleanup(files);
            }
        }
    }

    /** 按时间对齐合并两路分段（同一起点时左声道在前，稳定排序） */
    public static List<TranscriptSegment> merge(
            List<TranscriptSegment> left, List<TranscriptSegment> right) {
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

    private List<TranscriptSegment> tag(
            List<TranscriptSegment> segments, String speaker, String channel) {
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
