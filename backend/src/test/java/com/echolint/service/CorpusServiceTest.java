package com.echolint.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.echolint.entity.CorpusEntry;
import com.echolint.exception.BizException;
import com.echolint.repository.CorpusEntryRepository;
import com.echolint.service.rag.RagIndexService;
import com.echolint.util.PageLimits;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;

/** 语料全量导出上限（超限 400、不静默截断）与列表分页 size 钳制。 */
@ExtendWith(MockitoExtension.class)
class CorpusServiceTest {

    @Mock private CorpusEntryRepository repository;
    @Mock private RagIndexService ragIndexService;

    private CorpusService service;

    @BeforeEach
    void setUp() {
        service = new CorpusService(repository, ragIndexService);
    }

    @Test
    void exportAllReturnsRowsWhenWithinLimit() {
        when(repository.count()).thenReturn(2L);
        when(repository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(entry(1L), entry(2L))));

        List<CorpusEntry> rows = service.exportAll(2000);

        assertEquals(2, rows.size());
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findAll(captor.capture());
        assertEquals(0, captor.getValue().getPageNumber());
        assertEquals(2000, captor.getValue().getPageSize());
    }

    @Test
    void exportAllAllowsExactlyAtLimit() {
        when(repository.count()).thenReturn(2000L);
        when(repository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(entry(1L))));

        List<CorpusEntry> rows = service.exportAll(2000);

        assertEquals(1, rows.size());
    }

    @Test
    void exportAllRejectsWhenCountExceedsLimit() {
        when(repository.count()).thenReturn(2001L);

        BizException error = assertThrows(BizException.class, () -> service.exportAll(2000));

        assertTrue(error.getMessage().contains("2000"), error.getMessage());
        assertEquals(HttpStatus.BAD_REQUEST, error.getStatus());
        verify(repository, never()).findAll(any(Pageable.class));
    }

    @Test
    void pageLimitsClampSizeToConfiguredRange() {
        assertEquals(1, PageLimits.clamp(0, 100));
        assertEquals(1, PageLimits.clamp(-5, 100));
        assertEquals(50, PageLimits.clamp(50, 100));
        assertEquals(100, PageLimits.clamp(100_000, 100));
    }

    private static CorpusEntry entry(long id) {
        CorpusEntry entry = new CorpusEntry();
        entry.setId(id);
        entry.setTranscript("待审文本");
        return entry;
    }
}
