package com.echolint.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * RAG 检索增强配置（Batch 6，前缀 {@code app.rag}）。
 *
 * <p>知识库/SOP 与人工复核语料在写入时异步向量化，复筛时按相似度召回并注入提示词。
 * embedding 复用 {@code ai_providers} 的 base-url / api-key，只额外指定模型与路径，
 * 因此密钥轮换、供应商停用与现有 AI 治理保持一致。</p>
 *
 * <p>默认关闭（{@code enabled=false}）：不发起任何 embedding 调用，复筛行为与旧版一致。
 * 所有数值型配置都提供钳制后的 {@code effectiveXxx()}，避免配置笔误放大成本或把 prompt 撑爆。</p>
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.rag")
public class RagProperties {

    /** 总开关；false 时不召回、不注入、不索引（启动回填也跳过） */
    private boolean enabled = false;

    /** 知识库召回条数 */
    private int topK = 4;

    /** 余弦相似度下限（0~1），低于该值的片段丢弃 */
    private double minScore = 0.25;

    /** 注入 prompt 的知识片段总字符上限 */
    private int maxContextChars = 2400;

    /** 语义 few-shot 条数（Phase A）；0 表示仍用旧的「最新 N 条」逻辑 */
    private int corpusExamples = 5;

    private Embedding embedding = new Embedding();
    private Chunk chunk = new Chunk();
    private Corpus corpus = new Corpus();
    private Index index = new Index();
    private Documents documents = new Documents();

    /** 知识库召回条数：1~20 */
    public int effectiveTopK() {
        return Math.min(20, Math.max(1, topK));
    }

    /** 相似度下限：0~1，NaN 回退 0.25 */
    public double effectiveMinScore() {
        if (Double.isNaN(minScore)) {
            return 0.25;
        }
        return Math.min(1.0, Math.max(0.0, minScore));
    }

    /** 注入字符上限：200~20000 */
    public int effectiveMaxContextChars() {
        return Math.min(20_000, Math.max(200, maxContextChars));
    }

    /** few-shot 条数：0~20（0 = 关闭语义 few-shot，走旧逻辑） */
    public int effectiveCorpusExamples() {
        return Math.min(20, Math.max(0, corpusExamples));
    }

    /** 单块字符数：100~4000 */
    public int effectiveChunkChars() {
        return Math.min(4000, Math.max(100, chunk == null ? 600 : chunk.getChars()));
    }

    /** 分块重叠：0 ~ chunk/2 */
    public int effectiveChunkOverlap() {
        int chars = effectiveChunkChars();
        int overlap = chunk == null ? 80 : chunk.getOverlap();
        return Math.min(chars / 2, Math.max(0, overlap));
    }

    /** 语料 embedding 截断长度：200~20000 */
    public int effectiveCorpusMaxChars() {
        return Math.min(20_000, Math.max(200, corpus == null ? 6000 : corpus.getMaxChars()));
    }

    /** 是否在 embedding 请求中显式发送 dimensions（默认 true；国内模型据此输出指定维度） */
    public boolean sendEmbeddingDimensions() {
        return embedding == null || embedding.isSendDimensions();
    }

    /** embedding 维度：1~16000（默认 1024，需与 HNSW 索引一致） */
    public int effectiveDimensions() {
        return Math.min(16_000, Math.max(1, embedding == null ? 1024 : embedding.getDimensions()));
    }

    /** 单批 embedding 条数：1~64 */
    public int effectiveBatchSize() {
        return Math.min(64, Math.max(1, embedding == null ? 8 : embedding.getBatchSize()));
    }

    /** embedding 请求超时：1~120 秒 */
    public long effectiveEmbeddingTimeoutSeconds() {
        long value = embedding == null ? 30 : embedding.getTimeoutSeconds();
        return Math.min(120, Math.max(1, value));
    }

    /** 异步索引线程数：1~8 */
    public int effectiveIndexPoolSize() {
        return Math.min(8, Math.max(1, index == null ? 2 : index.getPoolSize()));
    }

    /** 文档导入大小上限：1KB~100MB */
    public long effectiveDocumentMaxBytes() {
        long value = documents == null ? 20L * 1024 * 1024 : documents.getMaxBytes();
        return Math.min(100L * 1024 * 1024, Math.max(1024, value));
    }

    /** embedding 供应商 id（小写；空值回退 aliyun） */
    public String effectiveEmbeddingProviderId() {
        String value = embedding == null ? null : embedding.getProviderId();
        return StringUtils.hasText(value) ? value.trim().toLowerCase(Locale.ROOT) : "aliyun";
    }

    /** embedding 模型名（空值回退 qwen3.7-text-embedding） */
    public String effectiveEmbeddingModel() {
        String value = embedding == null ? null : embedding.getModel();
        return StringUtils.hasText(value) ? value.trim() : "qwen3.7-text-embedding";
    }

    /** embedding 路径：保证以 / 开头（阿里云百炼为 /embeddings） */
    public String effectiveEmbeddingPath() {
        String value = embedding == null ? null : embedding.getPath();
        if (!StringUtils.hasText(value)) {
            return "/embeddings";
        }
        String path = value.trim();
        return path.startsWith("/") ? path : "/" + path;
    }

    /** 允许导入的文档扩展名（小写，不含点） */
    public Set<String> allowedDocumentExtensions() {
        Set<String> out = new LinkedHashSet<>();
        if (documents != null && documents.getExtensions() != null) {
            for (String ext : documents.getExtensions()) {
                if (StringUtils.hasText(ext)) {
                    out.add(ext.trim().toLowerCase(Locale.ROOT).replaceFirst("^\\.", ""));
                }
            }
        }
        if (out.isEmpty()) {
            out.addAll(List.of("pdf", "docx", "txt", "md"));
        }
        return out;
    }

    /** 文档存储目录（相对路径基于 JVM 工作目录） */
    public String effectiveDocumentDir() {
        String dir = documents == null ? null : documents.getDir();
        return StringUtils.hasText(dir) ? dir.trim() : "data/knowledge-docs";
    }

    @Getter
    @Setter
    public static class Embedding {
        /** 复用 ai_providers 中的供应商（需已配置密钥）；默认阿里云百炼 */
        private String providerId = "aliyun";
        /** 阿里云百炼文本向量模型；默认 1024 维 */
        private String model = "qwen3.7-text-embedding";
        /** base-url 已包含 /compatible-mode/v1，这里只需要 /embeddings */
        private String path = "/embeddings";
        private int dimensions = 1024;
        private long timeoutSeconds = 30;
        private int batchSize = 8;
        /** 是否在请求体中显式发送 dimensions（阿里云 qwen3.7 / v3 / v4 支持） */
        private boolean sendDimensions = true;
    }

    @Getter
    @Setter
    public static class Chunk {
        private int chars = 600;
        private int overlap = 80;
    }

    @Getter
    @Setter
    public static class Corpus {
        private int maxChars = 6000;
    }

    @Getter
    @Setter
    public static class Index {
        private int poolSize = 2;
        private boolean startupBackfill = true;
    }

    @Getter
    @Setter
    public static class Documents {
        private String dir = "data/knowledge-docs";
        private long maxBytes = 20L * 1024 * 1024;
        private List<String> extensions = new ArrayList<>(List.of("pdf", "docx", "txt", "md"));
    }
}
