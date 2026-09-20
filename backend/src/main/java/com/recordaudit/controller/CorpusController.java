package com.recordaudit.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recordaudit.domain.CorpusLabel;
import com.recordaudit.domain.ViolationType;
import com.recordaudit.dto.CorpusRequest;
import com.recordaudit.entity.CorpusEntry;
import com.recordaudit.exception.BizException;
import com.recordaudit.service.CorpusService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 语料库：人工复检结论沉淀与导出
 */
@RestController
@RequestMapping("/api/corpus")
@RequiredArgsConstructor
public class CorpusController {

    private final CorpusService corpusService;
    private final ObjectMapper objectMapper;

    @GetMapping
    public Page<CorpusEntry> list(@RequestParam(required = false) String label,
                                  @RequestParam(required = false) String keyword,
                                  @RequestParam(defaultValue = "0") int page,
                                  @RequestParam(defaultValue = "10") int size) {
        CorpusLabel corpusLabel = label == null || label.isBlank() ? null : CorpusLabel.valueOf(label);
        return corpusService.query(corpusLabel, keyword, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id")));
    }

    @PostMapping
    public CorpusEntry create(@Valid @RequestBody CorpusRequest request) {
        CorpusLabel label = switch (request.label()) {
            case "VIOLATION" -> CorpusLabel.VIOLATION;
            case "COMPLIANT" -> CorpusLabel.COMPLIANT;
            default -> throw new BizException("label 仅支持 VIOLATION / COMPLIANT");
        };
        String type = label == CorpusLabel.VIOLATION
                ? ViolationType.fromCode(request.violationType()).name()
                : null;
        return corpusService.appendManual(request.transcript(), label, type, request.reason());
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        corpusService.delete(id);
    }

    @GetMapping("/export")
    public ResponseEntity<Resource> export() throws Exception {
        var entries = corpusService.exportAll();
        byte[] bytes = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(entries);
        String fileName = "corpus_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".json";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ByteArrayResource(bytes));
    }
}