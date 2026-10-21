package com.echolint.service.rag.doc;

import java.io.InputStream;

/**
 * 文档文本抽取器（RAG Phase D）。
 *
 * <p>实现按扩展名支持 pdf / docx / txt / md；由 {@link DocumentTextExtractors} 注册表聚合。
 * 抽取失败抛异常，由上层把知识库条目置为 FAILED 并记录 index_error，不阻塞上传接口。</p>
 */
public interface DocumentTextExtractor {

    /** 是否支持该扩展名（小写、不含点，如 pdf / docx / txt / md） */
    boolean supports(String extension);

    /**
     * 从文档流抽取纯文本。
     *
     * @param in 文档输入流（由调用方负责关闭；实现不应关闭它）
     * @return 纯文本（可能为空字符串）
     * @throws Exception 解析失败（损坏文件、加密 PDF、非预期格式等）
     */
    String extract(InputStream in) throws Exception;
}
