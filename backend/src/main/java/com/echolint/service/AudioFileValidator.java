package com.echolint.service;

import com.echolint.exception.BizException;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;

/**
 * 上传文件头预检：按扩展名校验 magic bytes，防止改后缀上传非音频内容。
 *
 * <p>只读取文件头（默认前 12 字节），配合上传时的单次流式落盘，不额外读取整个文件。
 * 白名单之外的扩展名（未来配置放开）不做内容约束，由配置层负责准入。</p>
 */
@Component
public class AudioFileValidator {

    /** 预检所需的文件头字节数：WAV/MP4 需要读取 offset 4~12 */
    public static final int HEADER_SIZE = 12;

    /** 支持按内容预检的扩展名（与 application.yml 中 app.allowed-extensions 对齐） */
    private static final Set<String> SUPPORTED = Set.of(
            "mp3", "wav", "flac", "ogg", "opus", "m4a", "mp4", "mov", "webm", "aac", "amr", "caf");

    private static final byte[] ID3 = "ID3".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] RIFF = "RIFF".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] WAVE = "WAVE".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] FLAC = "fLaC".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] OGG = "OggS".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] FTYP = "ftyp".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] MOOV = "moov".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] MDAT = "mdat".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] WIDE = "wide".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] FREE = "free".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] ADIF = "ADIF".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] AMR = "#!AMR".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] CAF = "caff".getBytes(StandardCharsets.US_ASCII);

    /**
     * 校验文件头与扩展名是否一致。
     *
     * @param extension 小写扩展名（不含点）
     * @param header    文件开头若干字节（建议 {@link #HEADER_SIZE} 字节；文件更短时传实际内容）
     * @throws BizException 400「文件内容与扩展名不符」
     */
    public void validate(String extension, byte[] header) {
        String ext = extension == null ? "" : extension.toLowerCase(Locale.ROOT);
        if (!SUPPORTED.contains(ext)) {
            return;
        }
        if (!matches(ext, header)) {
            throw new BizException("文件内容与扩展名不符");
        }
    }

    private boolean matches(String ext, byte[] h) {
        if (h == null || h.length == 0) {
            return false;
        }
        return switch (ext) {
            // ID3v2 标签，或无标签时的 MPEG 帧同步（0xFF 后 3 位全 1）
            case "mp3" -> startsWith(h, ID3) || syncAt(h, 0, 0xE0);
            // RIFF....WAVE
            case "wav" -> h.length >= 12 && startsWith(h, RIFF) && asciiEquals(h, 8, WAVE);
            case "flac" -> startsWith(h, FLAC);
            // Opus 通常封装在 Ogg 容器中
            case "ogg", "opus" -> startsWith(h, OGG);
            // ISO BMFF：第 4~8 字节为 "ftyp"
            case "m4a", "mp4" -> h.length >= 8 && asciiEquals(h, 4, FTYP);
            // QuickTime 可能没有 ftyp（直接以 moov/mdat/wide/free 原子开头）
            case "mov" -> h.length >= 8
                    && (asciiEquals(h, 4, FTYP) || asciiEquals(h, 4, MOOV)
                    || asciiEquals(h, 4, MDAT) || asciiEquals(h, 4, WIDE) || asciiEquals(h, 4, FREE));
            // EBML 头（Matroska/WebM）
            case "webm" -> h.length >= 4
                    && (h[0] & 0xFF) == 0x1A && (h[1] & 0xFF) == 0x45
                    && (h[2] & 0xFF) == 0xDF && (h[3] & 0xFF) == 0xA3;
            // ADTS 帧同步（0xFFF…）或 ADIF 头
            case "aac" -> startsWith(h, ADIF) || syncAt(h, 0, 0xF0);
            case "amr" -> startsWith(h, AMR);
            case "caf" -> startsWith(h, CAF);
            default -> true;
        };
    }

    /** 前两字节 0xFF + 指定的高位掩码（用于 MPEG 音频 / ADTS 帧同步） */
    private static boolean syncAt(byte[] h, int offset, int mask) {
        return h.length >= offset + 2
                && (h[offset] & 0xFF) == 0xFF
                && ((h[offset + 1] & mask) == mask);
    }

    private static boolean startsWith(byte[] h, byte[] prefix) {
        return h.length >= prefix.length && Arrays.equals(h, 0, prefix.length, prefix, 0, prefix.length);
    }

    private static boolean asciiEquals(byte[] h, int offset, byte[] expected) {
        if (h.length < offset + expected.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if (h[offset + i] != expected[i]) {
                return false;
            }
        }
        return true;
    }
}
