package com.echolint.service.rag;

import com.echolint.config.RagProperties;
import com.echolint.entity.KnowledgeEntry;
import com.echolint.exception.BizException;
import com.echolint.repository.KnowledgeEntryRepository;
import com.echolint.security.SecurityUtils;
import com.echolint.service.rag.doc.DocumentTextExtractor;
import com.echolint.service.rag.doc.DocumentTextExtractors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 文档导入（RAG Phase D）：PDF / DOCX / TXT / MD 上传 → 抽取文本 → 写入知识库 → 异步向量化。
 *
 * <p>安全与可靠性约定：</p>
 * <ul>
 *   <li>扩展名白名单 + 魔数校验 + 大小上限；文件名只用于展示，落盘名用内容 SHA-256；</li>
 *   <li>同一内容（SHA-256）重复导入返回 409，避免重复索引与重复计费；</li>
 *   <li>解析/索引异步执行，失败把条目标记 FAILED 并记录错误，不阻塞上传响应；</li>
 *   <li>抽取失败时正文为空，条目仍可删除/重试；文件保留在文档目录（不做自动清理）。</li>
 * </ul>
 */
@Slf4j
@Service
public class RagDocumentService {

    /** 与 KnowledgeRequest.content 的长度上限保持一致 */
    static final int MAX_CONTENT_CHARS = 100_000;

    private final KnowledgeEntryRepository knowledgeEntries;
    private final DocumentTextExtractors extractors;
    private final RagIndexService indexService;
    private final RagProperties properties;
    private final ThreadPoolTaskExecutor executor;

    /** 防止同一条目并发解析（重复上传/重试） */
    private final Set<Long> parsing = ConcurrentHashMap.newKeySet();

    public RagDocumentService(KnowledgeEntryRepository knowledgeEntries,
                              DocumentTextExtractors extractors,
                              RagIndexService indexService,
                              RagProperties properties,
                              @Qualifier("ragIndexExecutor") ThreadPoolTaskExecutor executor) {
        this.knowledgeEntries = knowledgeEntries;
        this.extractors = extractors;
        this.indexService = indexService;
        this.properties = properties;
        this.executor = executor;
    }

    /**
     * 导入一个文档：校验 → 落盘 → 建知识库条目（PENDING）→ 异步解析 + 索引。
     */
    public KnowledgeEntry importDocument(MultipartFile file, String title, String category) {
        if (file == null || file.isEmpty()) {
            throw new BizException("请选择要导入的文档", HttpStatus.BAD_REQUEST);
        }
        String original = safeOriginalName(file.getOriginalFilename());
        String extension = extensionOf(original);
        Set<String> allowed = properties.allowedDocumentExtensions();
        if (!allowed.contains(extension)) {
            throw new BizException("不支持的文档类型: " + (extension.isEmpty() ? "未知" : extension)
                    + "（支持 " + String.join(" / ", allowed) + "）", HttpStatus.BAD_REQUEST);
        }
        long size = file.getSize();
        if (size <= 0) {
            throw new BizException("文档为空", HttpStatus.BAD_REQUEST);
        }
        if (size > properties.effectiveDocumentMaxBytes()) {
            throw new BizException("文档超过大小上限（"
                    + (properties.effectiveDocumentMaxBytes() / 1024 / 1024) + "MB）", HttpStatus.BAD_REQUEST);
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new BizException("读取上传文档失败：" + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
        if (!magicMatches(extension, bytes)) {
            throw new BizException("文档内容与扩展名不匹配（" + extension + "）", HttpStatus.BAD_REQUEST);
        }
        String sha = RagIndexService.sha256(bytes);
        knowledgeEntries.findFirstBySourceSha256(sha).ifPresent(existing -> {
            throw new BizException("该文档已导入（知识库条目 #" + existing.getId() + "）", HttpStatus.CONFLICT);
        });

        Path stored = store(bytes, sha, extension);
        KnowledgeEntry entry = new KnowledgeEntry();
        entry.setTitle(StringUtils.hasText(title) ? truncate(title.trim(), 200)
                : truncate(baseName(original), 200));
        entry.setCategory(StringUtils.hasText(category) ? truncate(category.trim(), 64) : null);
        entry.setContent(null);
        entry.setEnabled(true);
        entry.setCreatedBy(SecurityUtils.currentUsername().orElse("system"));
        entry.setSourceType("DOCUMENT");
        entry.setSourceFilename(truncate(original, 255));
        entry.setSourceSha256(sha);
        entry.setSourceSize(size);
        entry.setIndexStatus("PENDING");
        entry.setChunkCount(0);
        KnowledgeEntry saved = knowledgeEntries.save(entry);
        log.info("文档已导入：entryId={} file={} size={} ext={}", saved.getId(), original, size, extension);

        executor.execute(() -> parseAndIndex(saved.getId(), stored, extension));
        return saved;
    }

    // ------------------------------------------------------------------ 异步解析

    private void parseAndIndex(Long entryId, Path stored, String extension) {
        if (!parsing.add(entryId)) {
            return;
        }
        try {
            KnowledgeEntry entry = knowledgeEntries.findById(entryId).orElse(null);
            if (entry == null) {
                return;
            }
            entry.setIndexStatus("PARSING");
            entry.setIndexError(null);
            knowledgeEntries.save(entry);

            String text;
            try (InputStream in = Files.newInputStream(stored)) {
                DocumentTextExtractor extractor = extractors.find(extension)
                        .orElseThrow(() -> new BizException("没有可用的文档抽取器: " + extension));
                text = extractor.extract(in);
            }
            text = normalize(text);
            if (!StringUtils.hasText(text)) {
                throw new BizException("未能从文档中提取到文本（可能是扫描件或加密文件）");
            }
            if (text.length() > MAX_CONTENT_CHARS) {
                log.warn("文档 {} 抽取文本 {} 字符，截断到 {}", entryId, text.length(), MAX_CONTENT_CHARS);
                text = text.substring(0, MAX_CONTENT_CHARS);
            }
            entry = knowledgeEntries.findById(entryId).orElse(null);
            if (entry == null) {
                return;
            }
            entry.setContent(text);
            entry.setIndexError(null);
            knowledgeEntries.save(entry);
            log.info("文档解析完成：entryId={} 字符数={}", entryId, text.length());
            // 同步调用索引；内部会把状态置 INDEXING → READY/FAILED
            indexService.indexKnowledgeNow(entryId);
        } catch (Exception e) {
            log.warn("文档解析失败 entryId={}: {}", entryId, e.getMessage());
            markFailed(entryId, e.getMessage());
        } finally {
            parsing.remove(entryId);
        }
    }

    private void markFailed(Long entryId, String error) {
        try {
            knowledgeEntries.findById(entryId).ifPresent(entry -> {
                entry.setIndexStatus("FAILED");
                entry.setIndexError(truncate(error == null ? "未知错误" : error, 1000));
                knowledgeEntries.save(entry);
            });
        } catch (Exception e) {
            log.warn("标记文档失败状态出错 entryId={}: {}", entryId, e.getMessage());
        }
    }

    // ------------------------------------------------------------------ 校验/落盘

    private Path store(byte[] bytes, String sha, String extension) {
        try {
            Path dir = Path.of(properties.effectiveDocumentDir()).toAbsolutePath().normalize();
            Files.createDirectories(dir);
            Path target = dir.resolve(sha + "." + extension).normalize();
            if (!target.startsWith(dir)) {
                throw new BizException("非法文档存储路径", HttpStatus.BAD_REQUEST);
            }
            if (!Files.exists(target)) {
                Files.write(target, bytes);
            }
            return target;
        } catch (IOException e) {
            throw new BizException("保存文档失败：" + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private static boolean magicMatches(String extension, byte[] bytes) {
        if ("pdf".equals(extension)) {
            return bytes.length >= 5 && bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D'
                    && bytes[3] == 'F' && bytes[4] == '-';
        }
        if ("docx".equals(extension)) {
            return bytes.length >= 4 && bytes[0] == 'P' && bytes[1] == 'K'
                    && (bytes[2] == 3 || bytes[2] == 5) && (bytes[3] == 4 || bytes[3] == 6);
        }
        // txt/md：前 1024 字节出现 NUL 视为二进制，拒绝
        int limit = Math.min(1024, bytes.length);
        for (int i = 0; i < limit; i++) {
            if (bytes[i] == 0) {
                return false;
            }
        }
        return true;
    }

    static String safeOriginalName(String raw) {
        if (!StringUtils.hasText(raw)) {
            throw new BizException("文档缺少文件名", HttpStatus.BAD_REQUEST);
        }
        String name;
        try {
            Path path = Path.of(raw);
            name = path.getFileName() == null ? "" : path.getFileName().toString();
        } catch (Exception e) {
            name = raw.replace('\\', '/');
            int slash = name.lastIndexOf('/');
            name = slash >= 0 ? name.substring(slash + 1) : name;
        }
        name = name.replaceAll("[\\p{Cntrl}]", "").trim();
        if (!StringUtils.hasText(name)) {
            throw new BizException("文档文件名非法", HttpStatus.BAD_REQUEST);
        }
        return name;
    }

    static String extensionOf(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    static String baseName(String filename) {
        int dot = filename.lastIndexOf('.');
        String base = dot <= 0 ? filename : filename.substring(0, dot);
        return StringUtils.hasText(base) ? base : "未命名文档";
    }

    static String normalize(String text) {
        if (text == null) {
            return "";
        }
        String value = text.replace("\r\n", "\n").replace('\r', '\n');
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\n' || c == '\t' || !Character.isISOControl(c)) {
                sb.append(c);
            }
        }
        return sb.toString().strip();
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

}
