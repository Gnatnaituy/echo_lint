package com.echolint.service.rag.doc;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 纯文本抽取器（RAG Phase D）：支持 txt / md。
 *
 * <p>解码策略：先去除 UTF-8 BOM，再按严格 UTF-8 解码；遇到非法字节时回退 GB18030 （覆盖 GBK / GB2312 等中文 Windows
 * 常见编码）。随后统一换行、去控制字符。 空文件或纯空白内容返回空字符串。
 */
@Component
public class PlainTextDocumentTextExtractor implements DocumentTextExtractor {

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("txt", "md");
    private static final Charset GB18030 = Charset.forName("GB18030");
    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    @Override
    public boolean supports(String extension) {
        return extension != null
                && SUPPORTED_EXTENSIONS.contains(extension.toLowerCase(Locale.ROOT));
    }

    @Override
    public String extract(InputStream in) throws IOException {
        if (in == null) {
            throw new IllegalArgumentException("纯文本输入流不能为 null");
        }
        // 内存保护（P2-14）：最多读取「字符上限 × 4 字节 + 4」——UTF-8/GB18030 单字符最多 4 字节，
        // 足够解出上限字符；避免为超长纯文本把整份文件读进堆。
        byte[] bytes = readAtMost(in, DocumentTextExtractor.MAX_TEXT_CHARS * 4 + 4);
        String text = DocumentTextNormalizer.normalize(decode(stripUtf8Bom(bytes)));
        return text.length() <= DocumentTextExtractor.MAX_TEXT_CHARS
                ? text
                : text.substring(0, DocumentTextExtractor.MAX_TEXT_CHARS);
    }

    /** 最多读取 maxBytes 字节（不关闭入参流；调用方仍持有流并负责关闭） */
    private static byte[] readAtMost(InputStream in, int maxBytes) throws IOException {
        return in.readNBytes(maxBytes);
    }

    /** 严格 UTF-8 优先，非法 UTF-8 字节回退 GB18030 */
    private static String decode(byte[] bytes) {
        try {
            CharsetDecoder decoder =
                    StandardCharsets.UTF_8
                            .newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT);
            return decoder.decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            return new String(bytes, GB18030);
        }
    }

    /** 去除 UTF-8 BOM（EF BB BF）；不含 BOM 时原样返回 */
    private static byte[] stripUtf8Bom(byte[] bytes) {
        if (bytes.length >= UTF8_BOM.length
                && bytes[0] == UTF8_BOM[0]
                && bytes[1] == UTF8_BOM[1]
                && bytes[2] == UTF8_BOM[2]) {
            byte[] stripped = new byte[bytes.length - UTF8_BOM.length];
            System.arraycopy(bytes, UTF8_BOM.length, stripped, 0, stripped.length);
            return stripped;
        }
        return bytes;
    }
}
