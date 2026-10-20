package com.echolint.service.rag;

import com.echolint.config.RagProperties;
import com.echolint.entity.AppSetting;
import com.echolint.repository.AppSettingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * RAG 运行时设置：默认值、持久化、缓存刷新与数值钳制。
 */
@ExtendWith(MockitoExtension.class)
class RagSettingsServiceTest {

    @Mock
    private AppSettingRepository settings;

    private RagProperties properties;
    private RagSettingsService service;

    @BeforeEach
    void setUp() {
        properties = new RagProperties();
        properties.setEnabled(false);
        properties.setTopK(4);
        properties.setMinScore(0.25);
        properties.setMaxContextChars(2400);
        properties.setCorpusExamples(5);
        lenient().when(settings.findById(anyString())).thenReturn(Optional.empty());
        service = new RagSettingsService(settings, properties);
    }

    @Test
    void defaultsComeFromPropertiesWhenNoSettingsStored() {
        RagSettingsService.Snapshot snapshot = service.current();

        assertFalse(snapshot.enabled());
        assertEquals(4, snapshot.topK());
        assertEquals(0.25, snapshot.minScore(), 1e-9);
        assertEquals(2400, snapshot.maxContextChars());
        assertEquals(5, snapshot.corpusExamples());
        assertEquals("defaults", snapshot.source());
    }

    @Test
    void readsPersistedSettingsAndNormalizesSource() {
        when(settings.findById("rag.enabled")).thenReturn(Optional.of(AppSetting.of("rag.enabled", "true")));
        when(settings.findById("rag.top-k")).thenReturn(Optional.of(AppSetting.of("rag.top-k", "8")));
        when(settings.findById("rag.min-score")).thenReturn(Optional.of(AppSetting.of("rag.min-score", "0.4")));
        when(settings.findById("rag.max-context-chars"))
                .thenReturn(Optional.of(AppSetting.of("rag.max-context-chars", "1200")));
        when(settings.findById("rag.corpus-examples"))
                .thenReturn(Optional.of(AppSetting.of("rag.corpus-examples", "3")));

        RagSettingsService.Snapshot snapshot = service.current();

        assertTrue(snapshot.enabled());
        assertEquals(8, snapshot.topK());
        assertEquals(0.4, snapshot.minScore(), 1e-9);
        assertEquals(1200, snapshot.maxContextChars());
        assertEquals(3, snapshot.corpusExamples());
        assertEquals("settings", snapshot.source());
    }

    @Test
    void updatePersistsAllKeysAndRefreshesSnapshot() {
        RagSettingsService.Snapshot snapshot = service.update(true, 6, 0.3, 3000, 8);

        assertTrue(snapshot.enabled());
        assertEquals(6, snapshot.topK());
        assertEquals("settings", snapshot.source());
        verify(settings, atLeastOnce()).save(any(AppSetting.class));
        ArgumentCaptor<AppSetting> captor = ArgumentCaptor.forClass(AppSetting.class);
        verify(settings, times(5)).save(captor.capture());
        assertTrue(captor.getAllValues().stream().anyMatch(s -> s.getSettingKey().equals("rag.enabled")
                && s.getSettingValue().equals("true")));
        assertTrue(captor.getAllValues().stream().anyMatch(s -> s.getSettingKey().equals("rag.top-k")
                && s.getSettingValue().equals("6")));
    }

    @Test
    void updateClampsInvalidValues() {
        RagSettingsService.Snapshot snapshot = service.update(true, 999, 2.5, 5, -3);

        assertEquals(20, snapshot.topK(), "topK 上限 20");
        assertEquals(1.0, snapshot.minScore(), 1e-9, "minScore 上限 1");
        assertEquals(200, snapshot.maxContextChars(), "字符预算下限 200");
        assertEquals(0, snapshot.corpusExamples(), "few-shot 条数下限 0");
    }

    @Test
    void nullFieldsKeepCurrentValues() {
        RagSettingsService.Snapshot first = service.update(true, 7, 0.5, 3000, 6);
        RagSettingsService.Snapshot second = service.update(null, null, null, null, null);

        assertEquals(first.topK(), second.topK());
        assertEquals(first.minScore(), second.minScore());
        assertTrue(second.enabled());
    }

    @Test
    void lastReindexAtIsReadable() {
        when(settings.findById("rag.last-reindex-at"))
                .thenReturn(Optional.of(AppSetting.of("rag.last-reindex-at", "2026-10-20T21:00:00")));

        assertEquals("2026-10-20T21:00:00", service.lastReindexAt().orElseThrow());
    }

    @Test
    void markReindexedWritesTimestamp() {
        service.markReindexedNow();

        ArgumentCaptor<AppSetting> captor = ArgumentCaptor.forClass(AppSetting.class);
        verify(settings).save(captor.capture());
        assertEquals("rag.last-reindex-at", captor.getValue().getSettingKey());
        assertFalse(captor.getValue().getSettingValue().isBlank());
    }
}
