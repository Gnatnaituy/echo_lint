package com.echolint.service;

import com.echolint.domain.CorpusLabel;
import com.echolint.entity.CorpusEntry;
import com.echolint.entity.Recording;
import com.echolint.exception.BizException;
import com.echolint.repository.CorpusEntryRepository;
import com.echolint.service.AiScreenResult.CorpusExample;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 语料库服务：人工复检结论沉淀 + 复筛 few-shot 取材
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CorpusService {

    private final CorpusEntryRepository repository;

    /**
     * 复筛 few-shot：从已标注语料中取各 label 最新的若干条
     */
    public List<CorpusExample> getFewShotExamples(int count) {
        int perLabel = Math.max(1, count / 2);
        List<CorpusExample> out = new ArrayList<>();
        for (CorpusLabel label : List.of(CorpusLabel.VIOLATION, CorpusLabel.COMPLIANT)) {
            List<CorpusEntry> entries = repository.findByLabelOrderByIdDesc(label, PageRequest.of(0, perLabel));
            for (CorpusEntry e : entries) {
                out.add(new CorpusExample(e.getLabel(), e.getViolationType(), truncate(e.getTranscript(), 400)));
            }
        }
        return out;
    }

    /**
     * 人工复检结论回馈语料库
     */
    public CorpusEntry append(Recording recording, CorpusLabel label, String violationType, String reason) {
        CorpusEntry entry = new CorpusEntry();
        entry.setLabel(label);
        entry.setTranscript(recording.getTranscript());
        entry.setViolationType(violationType);
        entry.setReason(StringUtils.hasText(reason) ? reason : null);
        entry.setRecordingId(recording.getId());
        return repository.save(entry);
    }

    public CorpusEntry appendManual(String transcript, CorpusLabel label, String violationType, String reason) {
        CorpusEntry entry = new CorpusEntry();
        entry.setLabel(label);
        entry.setTranscript(transcript);
        entry.setViolationType(violationType);
        entry.setReason(reason);
        entry.setSource("MANUAL");
        return repository.save(entry);
    }

    public Page<CorpusEntry> query(CorpusLabel label, String keyword, Pageable pageable) {
        Specification<CorpusEntry> spec = (root, q, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (label != null) {
                ps.add(cb.equal(root.get("label"), label));
            }
            if (StringUtils.hasText(keyword)) {
                String kw = "%" + keyword.toLowerCase(Locale.ROOT) + "%";
                ps.add(cb.like(cb.lower(root.get("transcript")), kw));
            }
            return cb.and(ps.toArray(Predicate[]::new));
        };
        return repository.findAll(spec, pageable);
    }

    public List<CorpusEntry> exportAll() {
        return repository.findAll();
    }

    public void delete(Long id) {
        CorpusEntry entry = repository.findById(id)
                .orElseThrow(() -> new BizException("语料不存在", HttpStatus.NOT_FOUND));
        repository.delete(entry);
        log.info("语料已删除 id={}", id);
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}