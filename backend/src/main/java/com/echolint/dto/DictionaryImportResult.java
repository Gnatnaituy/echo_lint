package com.echolint.dto;

/**
 * 词库 CSV 导入结果：成功新增 / 跳过（重复）/ 无效行。
 */
public record DictionaryImportResult(int created, int skipped, int invalid) {
}
