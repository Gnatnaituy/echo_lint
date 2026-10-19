package com.echolint.controller;

import com.echolint.dfa.DfaHit;
import com.echolint.domain.AuditAction;
import com.echolint.domain.WordSeverity;
import com.echolint.domain.WordSource;
import com.echolint.dto.BulkEnabledRequest;
import com.echolint.dto.BulkUpdateResult;
import com.echolint.dto.DictWordRequest;
import com.echolint.dto.DictionaryImportResult;
import com.echolint.dto.TestTextRequest;
import com.echolint.entity.DictionaryWord;
import com.echolint.exception.BizException;
import com.echolint.service.AuditService;
import com.echolint.service.DfaService;
import com.echolint.service.DictionaryOpsService;
import com.echolint.dfa.WordNormalizer;
import com.echolint.repository.DictionaryWordRepository;
import jakarta.persistence.criteria.Predicate;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 敏感词词典管理（CRUD 后自动重建 DFA 匹配器）。
 *
 * <p>Batch 2a 起提供词库运营接口：CSV 导出、CSV 导入、批量启用/停用（均仅 ADMIN）。
 * 写操作会重建 DFA 并写审计；导出为纯查询，不写审计。</p>
 */
@RestController
@RequestMapping("/api/dictionary")
@RequiredArgsConstructor
public class DictionaryController {

    private static final MediaType CSV = new MediaType("text", "csv", StandardCharsets.UTF_8);

    private final DictionaryWordRepository repository;
    private final DfaService dfaService;
    private final AuditService auditService;
    private final DictionaryOpsService dictionaryOpsService;

    @GetMapping
    public Page<DictionaryWord> list(@RequestParam(required = false) String keyword,
                                     @RequestParam(required = false) String source,
                                     @RequestParam(required = false) Boolean enabled,
                                     @RequestParam(defaultValue = "0") int page,
                                     @RequestParam(defaultValue = "20") int size) {
        Specification<DictionaryWord> spec = (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (StringUtils.hasText(keyword)) {
                String kw = "%" + keyword.trim().toLowerCase(Locale.ROOT) + "%";
                ps.add(cb.like(cb.lower(root.get("word")), kw));
            }
            if (StringUtils.hasText(source)) {
                ps.add(cb.equal(root.get("source"), WordSource.valueOf(source)));
            }
            if (enabled != null) {
                ps.add(cb.equal(root.get("enabled"), enabled));
            }
            return cb.and(ps.toArray(Predicate[]::new));
        };
        return repository.findAll(spec, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id")));
    }

    @PostMapping
    public DictionaryWord create(@Valid @RequestBody DictWordRequest request) {
        String normalized = WordNormalizer.normalize(request.word());
        if (normalized.isEmpty()) {
            throw new BizException("词规范化后为空（仅支持文字与数字）");
        }
        repository.findByWordIgnoreCase(normalized).ifPresent(existing -> {
            throw new BizException("词已存在: " + existing.getWord());
        });
        DictionaryWord word = new DictionaryWord();
        word.setWord(normalized);
        word.setCategory(request.category());
        word.setSeverity(request.severity() == null ? WordSeverity.MEDIUM : WordSeverity.valueOf(request.severity()));
        word.setSource(WordSource.MANUAL);
        word.setEnabled(request.enabled() == null || request.enabled());
        DictionaryWord saved = repository.save(word);
        dfaService.rebuild();
        auditService.record(AuditAction.DICTIONARY_CREATE, "DICTIONARY_WORD", saved.getId(),
                "新增敏感词 " + saved.getWord());
        return saved;
    }

    @PutMapping("/{id}")
    public DictionaryWord update(@PathVariable Long id, @Valid @RequestBody DictWordRequest request) {
        DictionaryWord word = repository.findById(id)
                .orElseThrow(() -> new BizException("词不存在: " + id, HttpStatus.NOT_FOUND));
        if (StringUtils.hasText(request.word())) {
            String normalized = WordNormalizer.normalize(request.word());
            repository.findByWordIgnoreCase(normalized).ifPresent(existing -> {
                if (!existing.getId().equals(id)) {
                    throw new BizException("词已存在: " + existing.getWord());
                }
            });
            word.setWord(normalized);
        }
        if (StringUtils.hasText(request.category())) {
            word.setCategory(request.category());
        }
        if (StringUtils.hasText(request.severity())) {
            word.setSeverity(WordSeverity.valueOf(request.severity()));
        }
        if (request.enabled() != null) {
            word.setEnabled(request.enabled());
        }
        DictionaryWord saved = repository.save(word);
        dfaService.rebuild();
        auditService.record(AuditAction.DICTIONARY_UPDATE, "DICTIONARY_WORD", saved.getId(),
                "更新敏感词 " + saved.getWord());
        return saved;
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        DictionaryWord word = repository.findById(id).orElse(null);
        repository.deleteById(id);
        dfaService.rebuild();
        auditService.record(AuditAction.DICTIONARY_DELETE, "DICTIONARY_WORD", id,
                word == null ? "删除敏感词" : "删除敏感词 " + word.getWord());
    }

    /**
     * 检测工具：输入文本，返回当前启用词典的命中
     */
    @PostMapping("/test")
    public List<DfaHit> test(@Valid @RequestBody TestTextRequest request) {
        List<DfaHit> hits = dfaService.match(request.text());
        return hits;
    }

    /** 词库导出 CSV（UTF-8 BOM） */
    @GetMapping("/export")
    public ResponseEntity<byte[]> export() {
        String filename = DictionaryOpsService.exportFileName(LocalDateTime.now());
        return ResponseEntity.ok()
                .contentType(CSV)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(dictionaryOpsService.exportCsv());
    }

    /** 词库导入 CSV（multipart，field=file），返回 {created, skipped, invalid} */
    @PostMapping("/import")
    public DictionaryImportResult importCsv(@RequestParam("file") MultipartFile file) {
        return dictionaryOpsService.importCsv(file);
    }

    /** 批量启用/停用，body {ids, enabled}，返回 {updated} */
    @PatchMapping("/bulk-enabled")
    public BulkUpdateResult bulkEnabled(@Valid @RequestBody BulkEnabledRequest request) {
        return new BulkUpdateResult(
                dictionaryOpsService.bulkEnabled(request.ids(), request.enabled()));
    }
}