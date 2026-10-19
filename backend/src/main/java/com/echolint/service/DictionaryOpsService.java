package com.echolint.service;

import com.echolint.dfa.WordNormalizer;
import com.echolint.domain.AuditAction;
import com.echolint.domain.WordSeverity;
import com.echolint.domain.WordSource;
import com.echolint.dto.DictionaryImportResult;
import com.echolint.entity.DictionaryWord;
import com.echolint.exception.BizException;
import com.echolint.repository.DictionaryWordRepository;
import com.echolint.util.CsvParser;
import com.echolint.util.CsvWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * 敏感词库运营：CSV 导出 / CSV 导入 / 批量启用停用。
 *
 * <p>导入约束：≤{@value #MAX_IMPORT_ROWS} 行，允许表头（首格为 {@code word}），
 * 词经 {@link WordNormalizer} 规范化后与库内/文件内去重；重复计入 skipped，
 * 无法识别的行计入 invalid。导入/批量启停后重建 DFA 并写审计。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DictionaryOpsService {

    public static final int MAX_IMPORT_ROWS = 5000;
    public static final int MAX_BULK_IDS = 5000;
    /** 导入文件大小上限（5000 行纯文本远小于该值，防止超大文件占内存） */
    public static final long MAX_IMPORT_BYTES = 5L * 1024 * 1024;

    private static final int MAX_WORD_LENGTH = 128;
    private static final int MAX_CATEGORY_LENGTH = 32;

    private static final String[] EXPORT_HEADER = {
            "word", "category", "severity", "source", "enabled", "hitCount", "createdAt"};

    private final DictionaryWordRepository dictionaryWordRepository;
    private final DfaService dfaService;
    private final AuditService auditService;

    /** 全量导出（按 word 升序） */
    public byte[] exportCsv() {
        List<DictionaryWord> words = dictionaryWordRepository.findAll(Sort.by(Sort.Direction.ASC, "word"));
        List<String[]> lines = new ArrayList<>(words.size() + 1);
        lines.add(EXPORT_HEADER.clone());
        for (DictionaryWord word : words) {
            lines.add(new String[]{
                    word.getWord(),
                    word.getCategory(),
                    word.getSeverity() == null ? null : word.getSeverity().name(),
                    word.getSource() == null ? null : word.getSource().name(),
                    String.valueOf(Boolean.TRUE.equals(word.getEnabled())),
                    String.valueOf(word.getHitCount() == null ? 0 : word.getHitCount()),
                    word.getCreatedAt() == null ? null : String.valueOf(word.getCreatedAt())});
        }
        return CsvWriter.toBytes(lines);
    }

    /**
     * CSV 导入：列 word,category,severity,enabled（后三列可空，表头可省略）。
     */
    @Transactional
    public DictionaryImportResult importCsv(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException("导入文件为空", HttpStatus.BAD_REQUEST);
        }
        if (file.getSize() > MAX_IMPORT_BYTES) {
            throw new BizException("导入文件不能超过 " + (MAX_IMPORT_BYTES / 1024 / 1024) + "MB",
                    HttpStatus.BAD_REQUEST);
        }
        String content;
        try {
            content = new String(file.getBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new BizException("读取导入文件失败: " + e.getMessage(), HttpStatus.BAD_REQUEST);
        }

        List<List<String>> rows = CsvParser.parse(content);
        if (!rows.isEmpty() && isHeader(rows.get(0))) {
            rows = rows.subList(1, rows.size());
        }

        List<List<String>> dataRows = rows.stream().filter(row -> !isBlankRow(row)).toList();
        if (dataRows.size() > MAX_IMPORT_ROWS) {
            throw new BizException("单次最多导入 " + MAX_IMPORT_ROWS + " 行（本次 " + dataRows.size() + " 行）",
                    HttpStatus.BAD_REQUEST);
        }

        Set<String> knownWords = new HashSet<>();
        for (DictionaryWord existing : dictionaryWordRepository.findAll()) {
            knownWords.add(existing.getWord().toLowerCase(Locale.ROOT));
        }

        List<DictionaryWord> toCreate = new ArrayList<>();
        int skipped = 0;
        int invalid = 0;
        for (List<String> row : dataRows) {
            String normalized = WordNormalizer.normalize(cell(row, 0));
            WordSeverity severity = parseSeverity(cell(row, 2));
            String category = blankToNull(cell(row, 1));
            Boolean enabled = parseEnabled(cell(row, 3));

            if (normalized.isEmpty()
                    || normalized.length() > MAX_WORD_LENGTH
                    || (category != null && category.length() > MAX_CATEGORY_LENGTH)
                    || severity == null
                    || enabled == null) {
                invalid++;
                continue;
            }
            if (!knownWords.add(normalized.toLowerCase(Locale.ROOT))) {
                skipped++;
                continue;
            }
            DictionaryWord word = new DictionaryWord();
            word.setWord(normalized);
            word.setCategory(category);
            word.setSeverity(severity);
            word.setSource(WordSource.MANUAL);
            word.setEnabled(enabled);
            toCreate.add(word);
        }

        if (!toCreate.isEmpty()) {
            dictionaryWordRepository.saveAll(toCreate);
            dictionaryWordRepository.flush();
            dfaService.rebuild();
        }
        auditService.record(AuditAction.DICTIONARY_IMPORT, "DICTIONARY_WORD", null,
                "CSV 导入敏感词：新增 " + toCreate.size() + "，跳过重复 " + skipped
                        + "，无效 " + invalid + "（共 " + dataRows.size() + " 行）");
        log.info("词库 CSV 导入完成：created={}, skipped={}, invalid={}", toCreate.size(), skipped, invalid);
        return new DictionaryImportResult(toCreate.size(), skipped, invalid);
    }

    /**
     * 批量启用/停用。返回实际变更条数（已处于目标状态的不计入）。
     */
    @Transactional
    public int bulkEnabled(List<Long> ids, boolean enabled) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        if (ids.size() > MAX_BULK_IDS) {
            throw new BizException("单次最多操作 " + MAX_BULK_IDS + " 个词（本次 " + ids.size() + " 个）",
                    HttpStatus.BAD_REQUEST);
        }
        List<Long> uniqueIds = ids.stream().filter(Objects::nonNull).distinct().toList();
        if (uniqueIds.isEmpty()) {
            return 0;
        }
        List<DictionaryWord> words = dictionaryWordRepository.findAllById(uniqueIds);
        List<DictionaryWord> changed = new ArrayList<>();
        for (DictionaryWord word : words) {
            if (!Boolean.valueOf(enabled).equals(word.getEnabled())) {
                word.setEnabled(enabled);
                changed.add(word);
            }
        }
        if (!changed.isEmpty()) {
            dictionaryWordRepository.saveAll(changed);
            dictionaryWordRepository.flush();
            dfaService.rebuild();
        }
        auditService.record(AuditAction.DICTIONARY_BULK_UPDATE, "DICTIONARY_WORD", null,
                "批量" + (enabled ? "启用" : "停用") + "敏感词 " + changed.size() + " 个"
                        + "（提交 " + uniqueIds.size() + " 个）");
        log.info("词库批量{}：提交 {} 个，实际变更 {} 个", enabled ? "启用" : "停用", uniqueIds.size(), changed.size());
        return changed.size();
    }

    private static boolean isHeader(List<String> row) {
        return !row.isEmpty() && "word".equalsIgnoreCase(row.get(0).trim());
    }

    private static boolean isBlankRow(List<String> row) {
        return row.stream().allMatch(cell -> cell == null || cell.isBlank());
    }

    private static String cell(List<String> row, int index) {
        if (index >= row.size()) {
            return null;
        }
        String value = row.get(index);
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** 空 => MEDIUM；支持 HIGH/MEDIUM/LOW 与 高/中/低；其它返回 null（无效行） */
    private static WordSeverity parseSeverity(String raw) {
        if (raw == null || raw.isBlank()) {
            return WordSeverity.MEDIUM;
        }
        String value = raw.trim().toUpperCase(Locale.ROOT);
        return switch (value) {
            case "HIGH", "高", "高严重", "严重" -> WordSeverity.HIGH;
            case "MEDIUM", "中", "中严重", "一般" -> WordSeverity.MEDIUM;
            case "LOW", "低", "低严重", "轻微" -> WordSeverity.LOW;
            default -> null;
        };
    }

    /** 空 => 启用；支持 true/false、1/0、yes/no、启用/停用 等；其它返回 null（无效行） */
    private static Boolean parseEnabled(String raw) {
        if (raw == null || raw.isBlank()) {
            return Boolean.TRUE;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "true", "t", "1", "yes", "y", "on", "启用", "是", "开启" -> Boolean.TRUE;
            case "false", "f", "0", "no", "n", "off", "停用", "否", "禁用" -> Boolean.FALSE;
            default -> null;
        };
    }

    /** 导出文件名：dictionary-<yyyyMMdd>.csv */
    public static String exportFileName(LocalDateTime now) {
        return "dictionary-" + now.toLocalDate().toString().replace("-", "") + ".csv";
    }
}
