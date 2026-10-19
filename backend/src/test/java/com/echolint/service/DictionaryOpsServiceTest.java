package com.echolint.service;

import com.echolint.domain.AuditAction;
import com.echolint.domain.WordSeverity;
import com.echolint.domain.WordSource;
import com.echolint.dto.DictionaryImportResult;
import com.echolint.entity.DictionaryWord;
import com.echolint.exception.BizException;
import com.echolint.repository.DictionaryWordRepository;
import com.echolint.util.CsvWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Sort;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/**
 * 词库运营：CSV 导出、CSV 导入（表头/去重/规范化/无效行/上限）、批量启停与 DFA 重建。
 */
@ExtendWith(MockitoExtension.class)
class DictionaryOpsServiceTest {

    @Mock
    private DictionaryWordRepository repository;
    @Mock
    private DfaService dfaService;
    @Mock
    private AuditService auditService;

    private DictionaryOpsService service;

    @BeforeEach
    void setUp() {
        service = new DictionaryOpsService(repository, dfaService, auditService);
    }

    private DictionaryWord word(String word, String category, WordSeverity severity,
                                WordSource source, boolean enabled, int hitCount) {
        DictionaryWord w = new DictionaryWord();
        w.setWord(word);
        w.setCategory(category);
        w.setSeverity(severity);
        w.setSource(source);
        w.setEnabled(enabled);
        w.setHitCount(hitCount);
        w.setCreatedAt(LocalDateTime.of(2026, 10, 20, 9, 30));
        return w;
    }

    private MockMultipartFile csv(String content) {
        return new MockMultipartFile("file", "dictionary.csv", "text/csv",
                content.getBytes(StandardCharsets.UTF_8));
    }

    private String decode(byte[] bytes) {
        assertArrayEquals(CsvWriter.UTF8_BOM,
                java.util.Arrays.copyOf(bytes, CsvWriter.UTF8_BOM.length), "导出应带 UTF-8 BOM");
        return new String(bytes, CsvWriter.UTF8_BOM.length,
                bytes.length - CsvWriter.UTF8_BOM.length, StandardCharsets.UTF_8);
    }

    @Test
    void exportWritesAllColumnsAndValues() {
        when(repository.findAll(any(Sort.class))).thenReturn(List.of(
                word("fuck", "INSULT", WordSeverity.HIGH, WordSource.MANUAL, true, 5)));

        String text = decode(service.exportCsv());

        String[] lines = text.split("\r\n");
        assertEquals("word,category,severity,source,enabled,hitCount,createdAt", lines[0]);
        assertEquals("fuck,INSULT,HIGH,MANUAL,true,5,2026-10-20T09:30", lines[1]);
    }

    @Test
    void importParsesHeaderNormalizesAndCountsSkippedAndInvalid() {
        when(repository.findAll()).thenReturn(List.of(
                word("shit", "INSULT", WordSeverity.MEDIUM, WordSource.MANUAL, true, 2)));
        String content = "word,category,severity,enabled\n"
                + "Fuck,INSULT,HIGH,true\n"
                + "同花顺,FINANCE,中,启用\n"
                + "FUCK,INSULT,LOW,false\n"
                + "shit,,,\n"
                + ",INSULT,HIGH,true\n"
                + "hello,INSULT,UNKNOWN,true\n"
                + "world,INSULT,HIGH,maybe\n"
                + "toolong," + "x".repeat(33) + ",HIGH,true\n";

        DictionaryImportResult result = service.importCsv(csv(content));

        assertEquals(2, result.created());
        assertEquals(2, result.skipped());
        assertEquals(4, result.invalid());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DictionaryWord>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(captor.capture());
        List<DictionaryWord> created = captor.getValue();
        assertEquals(2, created.size());
        DictionaryWord fuck = created.get(0);
        assertEquals("fuck", fuck.getWord());
        assertEquals("INSULT", fuck.getCategory());
        assertEquals(WordSeverity.HIGH, fuck.getSeverity());
        assertEquals(WordSource.MANUAL, fuck.getSource());
        assertEquals(Boolean.TRUE, fuck.getEnabled());
        DictionaryWord chinese = created.get(1);
        assertEquals("同花顺", chinese.getWord());
        assertEquals(WordSeverity.MEDIUM, chinese.getSeverity());
        assertEquals(Boolean.TRUE, chinese.getEnabled());

        verify(repository).flush();
        verify(dfaService).rebuild();
        verify(auditService).record(eq(AuditAction.DICTIONARY_IMPORT), eq("DICTIONARY_WORD"), isNull(),
                contains("新增 2"));
    }

    @Test
    void importWorksWithoutHeader() {
        when(repository.findAll()).thenReturn(List.of());

        DictionaryImportResult result = service.importCsv(csv("abc\n"));

        assertEquals(1, result.created());
        verify(dfaService).rebuild();
    }

    @Test
    void importWithOnlyDuplicatesSkipsDfaRebuild() {
        when(repository.findAll()).thenReturn(List.of(
                word("abc", null, WordSeverity.MEDIUM, WordSource.MANUAL, true, 0)));

        DictionaryImportResult result = service.importCsv(csv("ABC\n"));

        assertEquals(0, result.created());
        assertEquals(1, result.skipped());
        verify(repository, never()).saveAll(any());
        verify(dfaService, never()).rebuild();
        verify(auditService).record(eq(AuditAction.DICTIONARY_IMPORT), eq("DICTIONARY_WORD"), isNull(),
                contains("新增 0"));
    }

    @Test
    void importRejectsMoreThanFiveThousandRows() {
        String content = IntStream.range(0, DictionaryOpsService.MAX_IMPORT_ROWS + 1)
                .mapToObj(i -> "word" + i)
                .collect(Collectors.joining("\n"));

        BizException e = assertThrows(BizException.class, () -> service.importCsv(csv(content)));

        assertTrue(e.getMessage().contains("5000"));
        verify(repository, never()).saveAll(any());
    }

    @Test
    void importRejectsEmptyFile() {
        MockMultipartFile empty = new MockMultipartFile("file", "d.csv", "text/csv", new byte[0]);

        assertThrows(BizException.class, () -> service.importCsv(empty));
        verifyNoInteractions(repository, dfaService);
    }

    @Test
    void bulkEnabledUpdatesOnlyChangedWordsAndRebuildsDfa() {
        DictionaryWord enabled = word("aaa", null, WordSeverity.MEDIUM, WordSource.MANUAL, true, 0);
        enabled.setId(1L);
        DictionaryWord disabled = word("bbb", null, WordSeverity.MEDIUM, WordSource.MANUAL, false, 0);
        disabled.setId(2L);
        when(repository.findAllById(List.of(1L, 2L))).thenReturn(List.of(enabled, disabled));

        int updated = service.bulkEnabled(List.of(1L, 2L), true);

        assertEquals(1, updated);
        assertTrue(enabled.getEnabled());
        assertTrue(disabled.getEnabled());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DictionaryWord>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(captor.capture());
        assertEquals(List.of(disabled), captor.getValue());
        verify(dfaService).rebuild();
        verify(auditService).record(eq(AuditAction.DICTIONARY_BULK_UPDATE), eq("DICTIONARY_WORD"), isNull(),
                contains("启用敏感词 1 个"));
    }

    @Test
    void bulkEnabledWithoutActualChangeSkipsRebuildButStillAudits() {
        DictionaryWord enabled = word("aaa", null, WordSeverity.MEDIUM, WordSource.MANUAL, true, 0);
        enabled.setId(1L);
        when(repository.findAllById(List.of(1L))).thenReturn(List.of(enabled));

        int updated = service.bulkEnabled(List.of(1L), true);

        assertEquals(0, updated);
        verify(repository, never()).saveAll(any());
        verify(dfaService, never()).rebuild();
        verify(auditService).record(eq(AuditAction.DICTIONARY_BULK_UPDATE), eq("DICTIONARY_WORD"), isNull(),
                contains("启用敏感词 0 个"));
    }

    @Test
    void bulkEnabledHandlesEmptyAndOversizedIds() {
        assertEquals(0, service.bulkEnabled(null, false));
        assertEquals(0, service.bulkEnabled(List.of(), false));
        verifyNoInteractions(repository, dfaService, auditService);

        List<Long> tooMany = IntStream.range(0, DictionaryOpsService.MAX_BULK_IDS + 1)
                .mapToObj(Long::valueOf)
                .toList();
        assertThrows(BizException.class, () -> service.bulkEnabled(tooMany, true));
    }

    @Test
    void exportFileNameUsesDateSuffix() {
        assertEquals("dictionary-20261020.csv",
                DictionaryOpsService.exportFileName(LocalDateTime.of(2026, 10, 20, 12, 0)));
    }
}
