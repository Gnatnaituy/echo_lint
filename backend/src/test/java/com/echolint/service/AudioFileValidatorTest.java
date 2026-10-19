package com.echolint.service;

import com.echolint.exception.BizException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;

import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 文件头预检：合法容器头放行、改后缀的非音频内容 400、未知扩展名保持宽松。
 */
class AudioFileValidatorTest {

    private final AudioFileValidator validator = new AudioFileValidator();

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] concat(byte[]... parts) {
        int length = 0;
        for (byte[] part : parts) {
            length += part.length;
        }
        byte[] out = new byte[length];
        int offset = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, out, offset, part.length);
            offset += part.length;
        }
        return out;
    }

    private static byte[] zeros(int count) {
        return new byte[count];
    }

    static Stream<Arguments> validHeaders() {
        return Stream.of(
                Arguments.of("mp3", ascii("ID3"), "ID3v2 标签头"),
                Arguments.of("mp3", new byte[]{(byte) 0xFF, (byte) 0xFB, (byte) 0x90, 0x64}, "MPEG 帧同步"),
                Arguments.of("wav", concat(ascii("RIFF"), zeros(4), ascii("WAVE")), "RIFF/WAVE"),
                Arguments.of("flac", concat(ascii("fLaC"), zeros(4)), "fLaC"),
                Arguments.of("ogg", concat(ascii("OggS"), zeros(4)), "OggS"),
                Arguments.of("opus", concat(ascii("OggS"), zeros(4)), "Ogg 封装的 Opus"),
                Arguments.of("m4a", concat(zeros(4), ascii("ftyp"), zeros(4)), "ISO BMFF ftyp"),
                Arguments.of("mp4", concat(zeros(4), ascii("ftyp"), zeros(4)), "ISO BMFF ftyp"),
                Arguments.of("mov", concat(zeros(4), ascii("ftyp"), zeros(4)), "QuickTime ftyp"),
                Arguments.of("mov", concat(zeros(4), ascii("moov"), zeros(4)), "QuickTime moov 原子"),
                Arguments.of("webm", new byte[]{0x1A, 0x45, (byte) 0xDF, (byte) 0xA3}, "EBML 头"),
                Arguments.of("aac", new byte[]{(byte) 0xFF, (byte) 0xF1, 0x50, 0x40}, "ADTS 同步字"),
                Arguments.of("amr", concat(ascii("#!AMR"), zeros(2)), "#!AMR"),
                Arguments.of("caf", concat(ascii("caff"), zeros(4)), "caff"));
    }

    @ParameterizedTest(name = "{0} 合法头：{2}")
    @MethodSource("validHeaders")
    void acceptsValidMagicBytes(String extension, byte[] header, String description) {
        assertDoesNotThrow(() -> validator.validate(extension, header), description);
    }

    @Test
    void rejectsTextBytesDisguisedAsMp3() {
        BizException e = assertThrows(BizException.class,
                () -> validator.validate("mp3", ascii("this is not audio at all")));

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatus());
        assertEquals("文件内容与扩展名不符", e.getMessage());
    }

    @Test
    void rejectsWavContentWithTooShortHeader() {
        BizException e = assertThrows(BizException.class,
                () -> validator.validate("wav", ascii("RIFF")));

        assertEquals("文件内容与扩展名不符", e.getMessage());
    }

    @Test
    void rejectsEmptyHeader() {
        assertThrows(BizException.class, () -> validator.validate("flac", new byte[0]));
        assertThrows(BizException.class, () -> validator.validate("mp3", null));
    }

    @Test
    void extensionIsCaseInsensitive() {
        assertDoesNotThrow(() -> validator.validate("MP3", ascii("ID3")));
    }

    @Test
    void unknownExtensionIsNotContentChecked() {
        // 未来白名单放开的新格式不应被这里误杀，准入由 app.allowed-extensions 控制
        assertDoesNotThrow(() -> validator.validate("xwav", ascii("whatever")));
        assertDoesNotThrow(() -> validator.validate("", ascii("whatever")));
    }
}
