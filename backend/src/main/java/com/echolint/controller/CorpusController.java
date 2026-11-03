package com.echolint.controller;

import com.echolint.domain.AuditAction;
import com.echolint.domain.CorpusLabel;
import com.echolint.domain.ViolationType;
import com.echolint.dto.CorpusRequest;
import com.echolint.entity.CorpusEntry;
import com.echolint.exception.BizException;
import com.echolint.service.AuditService;
import com.echolint.service.CorpusService;
import com.echolint.util.PageLimits;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 语料库：人工复检结论沉淀与导出 */
@RestController
@RequestMapping("/api/corpus")
@RequiredArgsConstructor
public class CorpusController {

    /** 列表分页大小硬上限，防止一次拉全表（与其余列表接口一致） */
    private static final int MAX_PAGE_SIZE = 100;

    /** 语料全量导出上限：单次导出超过该行数直接 400，提示分批导出，避免整表 JSON 驻留内存 */
    static final int CORPUS_EXPORT_MAX_ROWS = 2000;

    private final CorpusService corpusService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    @GetMapping
    public Page<CorpusEntry> list(
            @RequestParam(required = false) String label,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        CorpusLabel corpusLabel =
                label == null || label.isBlank() ? null : CorpusLabel.valueOf(label);
        int safeSize = PageLimits.clamp(size, MAX_PAGE_SIZE);
        return corpusService.query(
                corpusLabel,
                keyword,
                PageRequest.of(page, safeSize, Sort.by(Sort.Direction.DESC, "id")));
    }

    @PostMapping
    public CorpusEntry create(@Valid @RequestBody CorpusRequest request) {
        CorpusLabel label =
                switch (request.label()) {
                    case "VIOLATION" -> CorpusLabel.VIOLATION;
                    case "COMPLIANT" -> CorpusLabel.COMPLIANT;
                    default -> throw new BizException("label 仅支持 VIOLATION / COMPLIANT");
                };
        String type =
                label == CorpusLabel.VIOLATION
                        ? ViolationType.fromCode(request.violationType()).name()
                        : null;
        CorpusEntry saved =
                corpusService.appendManual(request.transcript(), label, type, request.reason());
        auditService.record(
                AuditAction.CORPUS_CREATE,
                "CORPUS_ENTRY",
                saved.getId(),
                "新增语料 label=" + label + (type == null ? "" : "，类型 " + type));
        return saved;
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        corpusService.delete(id);
        auditService.record(AuditAction.CORPUS_DELETE, "CORPUS_ENTRY", id, "删除语料");
    }

    @GetMapping("/export")
    public ResponseEntity<Resource> export() throws Exception {
        var entries = corpusService.exportAll(CORPUS_EXPORT_MAX_ROWS);
        byte[] bytes = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(entries);
        String fileName =
                "corpus_"
                        + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
                        + ".json";
        return ResponseEntity.ok()
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + fileName + "\"")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ByteArrayResource(bytes));
    }
}
