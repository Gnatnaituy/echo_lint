package com.echolint.service;

import com.echolint.config.AppProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** 音频声道处理：探测声道数、按左右声道分离（ffmpeg）。 依赖系统 ffmpeg/ffprobe，缺失时优雅降级为单路转写。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AudioChannelService {

    private final AppProperties props;

    private volatile Boolean ffmpegAvailable;

    /** 分轨结果：左右声道各自的 mono 文件 */
    public record ChannelSplit(Path left, Path right) {}

    /**
     * @return 声道数；无法探测（无 ffprobe / 探测失败）返回 null
     */
    public Integer detectChannels(Path audioFile) {
        if (!isFfmpegAvailable()) {
            return null;
        }
        try {
            String out =
                    run(
                            List.of(
                                    "ffprobe",
                                    "-v",
                                    "error",
                                    "-select_streams",
                                    "a:0",
                                    "-show_entries",
                                    "stream=channels",
                                    "-of",
                                    "default=noprint_wrappers=1:nokey=1",
                                    audioFile.toString()),
                            30);
            String value = out.trim().lines().findFirst().orElse("").trim();
            if (value.isEmpty()) {
                return null;
            }
            return Integer.parseInt(value);
        } catch (Exception e) {
            log.warn("探测声道数失败（按单声道处理）: {}", e.getMessage());
            return null;
        }
    }

    /** 把立体声文件按左右声道拆成两个 mono WAV（与源文件同目录） */
    public Optional<ChannelSplit> splitStereo(Path audioFile) {
        if (!isFfmpegAvailable()) {
            return Optional.empty();
        }
        Path left = sibling(audioFile, ".L.wav");
        Path right = sibling(audioFile, ".R.wav");
        int timeout = props.getStereo().getSplitTimeoutSeconds();

        // 首选 channelsplit 滤镜（新版本推荐写法）
        List<String> filters =
                List.of(
                        "ffmpeg",
                        "-v",
                        "error",
                        "-y",
                        "-i",
                        audioFile.toString(),
                        "-filter_complex",
                        "[0:a]channelsplit=channel_layout=stereo[l][r]",
                        "-map",
                        "[l]",
                        "-ac",
                        "1",
                        left.toString(),
                        "-map",
                        "[r]",
                        "-ac",
                        "1",
                        right.toString());
        try {
            run(filters, timeout);
            if (Files.exists(left) && Files.exists(right)) {
                log.info(
                        "声道分离完成: {} → {}, {}",
                        audioFile.getFileName(),
                        left.getFileName(),
                        right.getFileName());
                return Optional.of(new ChannelSplit(left, right));
            }
        } catch (Exception e) {
            log.warn("channelsplit 分离失败，尝试 -map_channel 兼容写法: {}", e.getMessage());
        }

        // 兼容旧版 ffmpeg 的 -map_channel
        List<String> mapChannel =
                List.of(
                        "ffmpeg",
                        "-v",
                        "error",
                        "-y",
                        "-i",
                        audioFile.toString(),
                        "-map_channel",
                        "0.0.0",
                        "-ac",
                        "1",
                        left.toString(),
                        "-map_channel",
                        "0.0.1",
                        "-ac",
                        "1",
                        right.toString());
        try {
            run(mapChannel, timeout);
            if (Files.exists(left) && Files.exists(right)) {
                log.info("声道分离完成（map_channel）: {}", audioFile.getFileName());
                return Optional.of(new ChannelSplit(left, right));
            }
        } catch (Exception e) {
            log.warn("声道分离失败（按单路转写）: {}", e.getMessage());
        }
        cleanupPartial(left, right);
        return Optional.empty();
    }

    /**
     * 把多声道文件下混为 16kHz mono WAV（多声道降级单路转写时使用）。
     *
     * <p>云端 ASR（以及不少本地服务）默认只识别 0 声道；双声道拆轨失败/任一路转写失败后若直接 转写源文件，另一侧对话会被静默丢弃（漏审风险）。因此降级前先 downmix
     * 成单声道。
     *
     * @return 临时 mono 文件；ffmpeg 不可用或下混失败返回 empty（调用方自行决定是否退回源文件）
     */
    public Optional<Path> downmixToMono(Path audioFile) {
        if (!isFfmpegAvailable()) {
            return Optional.empty();
        }
        Path mono = sibling(audioFile, ".mono.wav");
        int timeout = props.getStereo().getSplitTimeoutSeconds();
        List<String> command =
                List.of(
                        "ffmpeg",
                        "-v",
                        "error",
                        "-y",
                        "-i",
                        audioFile.toString(),
                        "-ac",
                        "1",
                        "-ar",
                        "16000",
                        mono.toString());
        try {
            run(command, timeout);
            if (Files.exists(mono) && Files.size(mono) > 0) {
                log.info("多声道下混为单声道完成: {} → {}", audioFile.getFileName(), mono.getFileName());
                return Optional.of(mono);
            }
            log.warn("多声道下混命令执行成功但未生成有效文件: {}", audioFile.getFileName());
        } catch (Exception e) {
            log.warn("多声道下混为单声道失败（{}）: {}", audioFile.getFileName(), e.getMessage());
        }
        cleanupQuietly(mono);
        return Optional.empty();
    }

    /** 两种分离方案都失败时清理半截分轨文件，避免孤儿文件累积 */
    private void cleanupPartial(Path left, Path right) {
        for (Path p : List.of(left, right)) {
            cleanupQuietly(p);
        }
    }

    private void cleanupQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("清理临时文件失败 {}: {}", file.getFileName(), e.getMessage());
        }
    }

    public boolean isFfmpegAvailable() {
        Boolean cached = ffmpegAvailable;
        if (cached != null) {
            return cached;
        }
        boolean ok = false;
        try {
            run(List.of("ffmpeg", "-version"), 15);
            ok = true;
        } catch (Exception e) {
            log.warn("未检测到 ffmpeg，双声道分轨不可用（将按单路转写）: {}", e.getMessage());
        }
        ffmpegAvailable = ok;
        return ok;
    }

    private Path sibling(Path audioFile, String suffix) {
        return audioFile.resolveSibling(audioFile.getFileName().toString() + suffix);
    }

    private String run(List<String> command, int timeoutSeconds)
            throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        // 输出由独立线程消费：若在 waitFor 之前同步 readAllBytes，会一直阻塞到进程退出，
        // timeoutSeconds 形同虚设（ffmpeg 挂起时线程被无限占用）
        StringBuilder output = new StringBuilder();
        Thread reader =
                new Thread(
                        () -> {
                            try (var in = process.getInputStream()) {
                                byte[] buffer = new byte[8192];
                                int read;
                                while ((read = in.read(buffer)) != -1) {
                                    output.append(
                                            new String(buffer, 0, read, StandardCharsets.UTF_8));
                                }
                            } catch (IOException ignored) {
                                // 进程被销毁时管道关闭，读中断属预期
                            }
                        },
                        "ffmpeg-output-reader");
        reader.setDaemon(true);
        reader.start();
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            reader.join(1000);
            throw new IOException(
                    "命令超时: "
                            + String.join(
                                    " ",
                                    new ArrayList<>(command)
                                            .subList(0, Math.min(2, command.size()))));
        }
        reader.join(5000);
        if (process.exitValue() != 0) {
            throw new IOException(
                    "命令失败(" + process.exitValue() + "): " + abbreviate(output.toString()));
        }
        return output.toString();
    }

    private String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        String oneLine = s.replaceAll("\\s+", " ").trim();
        return oneLine.length() > 300 ? oneLine.substring(0, 300) : oneLine;
    }
}
