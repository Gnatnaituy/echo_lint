package com.echolint.service.rag.doc;

import org.springframework.stereotype.Component;

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

/**
 * 纯文本抽取器（RAG Phase D）：支持 txt / md。
 *
 * <p>解码策略：先去除 UTF-8 BOM，再按严格 UTF-8 解码；遇到非法字节时回退 GB18030
 * （覆盖 GBK / GB2312 等中文 Windows 常见编码）。随后统一换行、去控制字符。
 * 空文件或纯空白内容返回空字符串。</p>
 */
@Component
public class PlainTextDocumentTextExtractor implements DocumentTextExtractor {

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("txt", "md");
    private static final Charset GB18030 = Charset.forName("GB18030");
    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    @Override
    public boolean supports(String extension) {
        return extension != null && SUPPORTED_EXTENSIONS.contains(extension.toLowerCase(Locale.ROOT));
    }

    @Override
    public String extract(InputStream in) throws IOException {
        if (in == null) {
            throw new IllegalArgumentException("纯文本输入流不能为 null");
        }
        return DocumentTextNormalizer.normalize(decode(stripUtf8Bom(in.readAllBytes())));
    }

    /** 严格 UTF-8 优先，非法 UTF-8 字节回退 GB18030 */
    private static String decode(byte[] bytes) {
        try {
            CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
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
