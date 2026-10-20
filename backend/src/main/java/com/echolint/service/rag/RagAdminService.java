package com.echolint.service.rag;

import com.echolint.domain.RagIndexStatus;
import com.echolint.repository.CorpusEntryRepository;
import com.echolint.repository.KnowledgeEntryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * RAG 管理端视图与动作（Batch 6，Phase C）：设置读取/更新、连通性测试、重建索引、索引统计。
 *
 * <p>视图只读、不含任何密钥；embedding 供应商信息来自 {@code ai_providers} 的掩码配置。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagAdminService {

    private static final long VECTOR_CHECK_TTL_MS = 30_000;

    private final RagSettingsService settings;
    private final RagEmbeddingClient embeddingClient;
    private final RagIndexService indexService;
    private final KnowledgeEntryRepository knowledgeEntries;
    private final CorpusEntryRepository corpusEntries;
    private final JdbcTemplate jdbcTemplate;

    private volatile Boolean vectorAvailable;
    private volatile long vectorCheckedAt;

    public record EmbeddingView(String providerId, String providerLabel, String model, String path,
                                int dimensions, long timeoutSeconds, boolean apiKeyConfigured) {
    }

    public record RetrievalView(int topK, double minScore, int maxContextChars, int corpusExamples) {
    }

    public record IndexView(long knowledgeTotal, long knowledgeReady, long knowledgePending,
                            long knowledgeFailed, long corpusTotal, long corpusReady,
                            String lastReindexAt, boolean vectorAvailable) {
    }

    public record RagView(boolean enabled, String source, EmbeddingView embedding,
                          RetrievalView retrieval, IndexView index) {
    }

    public record ReindexResult(int queued, int skipped, String message) {
    }

    public RagView view() {
        RagSettingsService.Snapshot snapshot = settings.current();
        RagEmbeddingClient.EmbeddingInfo info = embeddingClient.info();
        long knowledgeTotal = safeCount(knowledgeEntries::count);
        long ready = safeCount(() -> knowledgeEntries.countByIndexStatus(RagIndexStatus.READY.name()));
        long failed = safeCount(() -> knowledgeEntries.countByIndexStatus(RagIndexStatus.FAILED.name()));
        long pending = Math.max(0, knowledgeTotal - ready - failed);
        long corpusTotal = safeCount(corpusEntries::count);
        long corpusReady = safeCount(corpusEntries::countByEmbeddingTextIsNotNull);
        return new RagView(
                snapshot.enabled(),
                snapshot.source(),
                new EmbeddingView(info.providerId(), info.providerLabel(), info.model(), info.path(),
                        info.dimensions(), info.timeoutSeconds(), info.apiKeyConfigured()),
                new RetrievalView(snapshot.topK(), snapshot.minScore(), snapshot.maxContextChars(),
                        snapshot.corpusExamples()),
                new IndexView(knowledgeTotal, ready, pending, failed, corpusTotal, corpusReady,
                        settings.lastReindexAt().orElse(null), vectorAvailable()));
    }

    public RagView update(Boolean enabled, Integer topK, Double minScore,
                          Integer maxContextChars, Integer corpusExamples) {
        boolean wasEnabled = settings.current().enabled();
        settings.update(enabled, topK, minScore, maxContextChars, corpusExamples);
        RagView view = view();
        // 从「关闭」切到「开启」时自动回填历史知识/语料，避免管理员还要多点一次重建
        if (!wasEnabled && view.enabled() && embeddingClient.available()) {
            indexService.backfillOnStartup();
        }
        return view;
    }

    public RagEmbeddingClient.TestResult test() {
        return embeddingClient.test();
    }

    /**
     * 异步重建索引。scope=knowledge|corpus|all；返回已入队数量（不是完成数）。
     */
    public ReindexResult reindex(String scope) {
        String value = scope == null ? "all" : scope.trim().toLowerCase();
        int queued = 0;
        if ("knowledge".equals(value) || "all".equals(value)) {
            queued += indexService.reindexKnowledge();
        }
        if ("corpus".equals(value) || "all".equals(value)) {
            queued += indexService.reindexCorpus();
        }
        if (!"knowledge".equals(value) && !"corpus".equals(value) && !"all".equals(value)) {
            return new ReindexResult(0, 0, "未知 scope：" + scope + "（支持 knowledge / corpus / all）");
        }
        settings.markReindexedNow();
        log.info("RAG 重建索引已入队：scope={} queued={}（异步执行）", value, queued);
        return new ReindexResult(queued, 0,
                "已入队 " + queued + " 条（异步执行，进度见索引统计）");
    }

    /** pgvector 扩展与分块表是否就绪；30 秒缓存，避免每次设置页刷新都查系统表 */
    public boolean vectorAvailable() {
        Boolean cached = vectorAvailable;
        long now = System.currentTimeMillis();
        if (cached != null && now - vectorCheckedAt < VECTOR_CHECK_TTL_MS) {
            return cached;
        }
        boolean available = checkVectorAvailable();
        vectorAvailable = available;
        vectorCheckedAt = now;
        return available;
    }

    private boolean checkVectorAvailable() {
        try {
            Integer extension = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM pg_extension WHERE extname = 'vector'", Integer.class);
            Integer table = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM pg_class WHERE relname = 'knowledge_chunks' AND relkind = 'r'",
                    Integer.class);
            return extension != null && extension > 0 && table != null && table > 0;
        } catch (Exception e) {
            log.warn("检查 pgvector 可用性失败：{}", e.getMessage());
            return false;
        }
    }

    private static long safeCount(CountSupplier supplier) {
        try {
            return supplier.get();
        } catch (Exception e) {
            return 0;
        }
    }

    @FunctionalInterface
    private interface CountSupplier {
        long get();
    }
}
