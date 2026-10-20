package com.echolint.service.rag;

import com.echolint.config.RagProperties;
import com.echolint.entity.KnowledgeEntry;
import com.echolint.exception.BizException;
import com.echolint.repository.KnowledgeEntryRepository;
import com.echolint.service.rag.doc.DocumentTextExtractor;
import com.echolint.service.rag.doc.DocumentTextExtractors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 文档导入：扩展名/魔数/大小/去重校验，异步解析后写正文并触发索引。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RagDocumentServiceTest {

    @Mock private KnowledgeEntryRepository knowledgeEntries;
    @Mock private DocumentTextExtractors extractors;
    @Mock private DocumentTextExtractor extractor;
    @Mock private RagIndexService indexService;
    @Mock private ThreadPoolTaskExecutor executor;

    @TempDir
    Path tempDir;

    private RagProperties properties;
    private RagDocumentService service;

    @BeforeEach
    void setUp() throws Exception {
        properties = new RagProperties();
        properties.getDocuments().setDir(tempDir.toString());
        properties.getDocuments().setMaxBytes(10 * 1024 * 1024);

        doAnswer(invocation -> {
            Runnable task = invocation.getArgument(0);
            task.run();
            return null;
        }).when(executor).execute(any());
        when(extractors.find("pdf")).thenReturn(Optional.of(extractor));
        when(extractors.find("txt")).thenReturn(Optional.of(extractor));
        when(extractors.find("md")).thenReturn(Optional.of(extractor));
        when(extractor.extract(any(InputStream.class))).thenReturn("文档正文内容");
        AtomicReference<KnowledgeEntry> saved = new AtomicReference<>();
        when(knowledgeEntries.save(any(KnowledgeEntry.class))).thenAnswer(inv -> {
            KnowledgeEntry entry = inv.getArgument(0);
            if (entry.getId() == null) {
                entry.setId(42L);
            }
            saved.set(entry);
            return entry;
        });
        when(knowledgeEntries.findById(42L)).thenAnswer(inv -> Optional.ofNullable(saved.get()));
        when(knowledgeEntries.findFirstBySourceSha256(any())).thenReturn(Optional.empty());

        service = new RagDocumentService(knowledgeEntries, extractors, indexService, properties, executor);
    }

    private MockMultipartFile pdf(String name, String content) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        byte[] withMagic = new byte[bytes.length + 8];
        System.arraycopy("%PDF-1.4".getBytes(StandardCharsets.UTF_8), 0, withMagic, 0, 8);
        System.arraycopy(bytes, 0, withMagic, 8, bytes.length);
        return new MockMultipartFile("file", name, "application/pdf", withMagic);
    }

    @Test
    void importsPdfAndParsesContent() throws Exception {
        MockMultipartFile file = pdf("sop.pdf", "hello");

        KnowledgeEntry entry = service.importDocument(file, "话术规范", "SOP");

        assertEquals(42L, entry.getId());
        assertEquals("话术规范", entry.getTitle());
        assertEquals("SOP", entry.getCategory());
        assertEquals("DOCUMENT", entry.getSourceType());
        assertEquals("sop.pdf", entry.getSourceFilename());
        // 异步解析同步执行完毕：状态已进入 PARSING（索引由 mock 的 indexService 负责推进）
        assertEquals("PARSING", entry.getIndexStatus());
        assertNotNull(entry.getSourceSha256());
        assertEquals(file.getSize(), entry.getSourceSize());
        assertEquals("文档正文内容", entry.getContent(), "异步解析后应写入正文");
        verify(indexService).indexKnowledgeNow(42L);
        verify(extractor).extract(any(InputStream.class));
    }

    @Test
    void generatesTitleFromFileNameWhenMissing() throws Exception {
        KnowledgeEntry entry = service.importDocument(pdf("退款-SOP.pdf", "x"), null, null);

        assertEquals("退款-SOP", entry.getTitle());
        assertNull(entry.getCategory());
    }

    @Test
    void rejectsUnsupportedExtension() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "a.exe", "application/octet-stream",
                new byte[]{1, 2, 3});
        BizException error = assertThrows(BizException.class, () -> service.importDocument(file, null, null));
        assertTrue(error.getMessage().contains("不支持的文档类型"));
    }

    @Test
    void rejectsMagicMismatch() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "fake.pdf", "application/pdf",
                "not a pdf".getBytes(StandardCharsets.UTF_8));
        BizException error = assertThrows(BizException.class, () -> service.importDocument(file, null, null));
        assertTrue(error.getMessage().contains("内容与扩展名不匹配"));
    }

    @Test
    void rejectsDuplicateContent() throws Exception {
        MockMultipartFile file = pdf("dup.pdf", "same");
        when(knowledgeEntries.findFirstBySourceSha256(any()))
                .thenReturn(Optional.of(entryWithId(7L)));

        BizException error = assertThrows(BizException.class, () -> service.importDocument(file, null, null));
        assertTrue(error.getMessage().contains("已导入"));
        assertTrue(error.getMessage().contains("#7"));
    }

    @Test
    void rejectsOversizedFile() throws Exception {
        properties.getDocuments().setMaxBytes(1024);
        BizException error = assertThrows(BizException.class,
                () -> service.importDocument(pdf("big.pdf", "x".repeat(2000)), null, null));
        assertTrue(error.getMessage().contains("大小上限"));
    }

    @Test
    void parseFailureMarksEntryFailed() throws Exception {
        when(extractor.extract(any(InputStream.class))).thenThrow(new RuntimeException("broken pdf"));

        service.importDocument(pdf("broken.pdf", "x"), "标题", null);

        ArgumentCaptor<KnowledgeEntry> captor = ArgumentCaptor.forClass(KnowledgeEntry.class);
        verify(knowledgeEntries, atLeast(2)).save(captor.capture());
        KnowledgeEntry last = captor.getValue();
        assertEquals("FAILED", last.getIndexStatus());
        assertTrue(last.getIndexError().contains("broken pdf"));
    }

    @Test
    void plainTextFileAccepted() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "notes.md", "text/markdown",
                "# 标题\n正文".getBytes(StandardCharsets.UTF_8));
        KnowledgeEntry entry = service.importDocument(file, null, null);

        assertEquals("notes", entry.getTitle());
        assertEquals("文档正文内容", entry.getContent());
    }

    @Test
    void sanitizesPathTraversalFileName() throws Exception {
        MockMultipartFile file = pdf("../../etc/passwd.pdf", "x");
        KnowledgeEntry entry = service.importDocument(file, null, null);

        assertEquals("passwd.pdf", entry.getSourceFilename());
    }

    private static KnowledgeEntry entryWithId(long id) {
        KnowledgeEntry entry = new KnowledgeEntry();
        entry.setId(id);
        return entry;
    }
}
