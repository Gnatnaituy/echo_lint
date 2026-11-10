package com.echolint.service.rag;

import com.echolint.config.RagProperties;
import com.echolint.domain.RagIndexStatus;
import com.echolint.entity.CorpusEntry;
import com.echolint.entity.KnowledgeChunk;
import com.echolint.entity.KnowledgeEntry;
import com.echolint.repository.CorpusEntryRepository;
import com.echolint.repository.KnowledgeChunkRepository;
import com.echolint.repository.KnowledgeEntryRepository;
import com.echolint.service.AiInvocationService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

/**
 * RAG 索引服务（Batch 6，Phase B/D）。
 *
 * <p>知识库条目：标题+分类+正文分块 → 批量 embedding → 写入 knowledge_chunks（替换旧分块）； 语料库：转写截断后整条 embedding（Phase A
 * 的语义 few-shot）。
 *
 * <p>索引全部异步执行（独立线程池），失败只把条目标记 FAILED 并记录错误， 不影响知识库 CRUD；管理员可在设置页手动重建。启动时按需回填历史数据。
 */
@Slf4j
@Service
public class RagIndexService {

    /** 单条知识最多分块数（防止异常长正文把 embedding 成本放大） */
    static final int MAX_CHUNKS_PER_ENTRY = 400;

    /** 重建索引的真实入队结果（P3-3）：queued = 线程池已接收，skipped = 队列满被拒绝。 管理端据此展示准确文案，不再把丢弃算成「已入队」。 */
    public record EnqueueResult(int queued, int skipped) {
        public EnqueueResult plus(EnqueueResult other) {
            return new EnqueueResult(queued + other.queued(), skipped + other.skipped());
        }
    }

    private final KnowledgeEntryRepository knowledgeEntries;
    private final KnowledgeChunkRepository chunks;
    private final CorpusEntryRepository corpusEntries;
    private final RagEmbeddingClient embeddingClient;
    private final RagSettingsService settings;
    private final RagProperties properties;
    private final RagEmbeddingSettingsResolver embeddingSettings;
    private final ThreadPoolTaskExecutor executor;

    /** 防止同一条目并发索引（手动重建 + 写入触发 + 启动回填） */
    private final Map<String, Boolean> running = new ConcurrentHashMap<>();

    public RagIndexService(
            KnowledgeEntryRepository knowledgeEntries,
            KnowledgeChunkRepository chunks,
            CorpusEntryRepository corpusEntries,
            RagEmbeddingClient embeddingClient,
            RagSettingsService settings,
            RagProperties properties,
            RagEmbeddingSettingsResolver embeddingSettings,
            @Qualifier("ragIndexExecutor") ThreadPoolTaskExecutor executor) {
        this.knowledgeEntries = knowledgeEntries;
        this.chunks = chunks;
        this.corpusEntries = corpusEntries;
        this.embeddingClient = embeddingClient;
        this.settings = settings;
        this.properties = properties;
        this.embeddingSettings = embeddingSettings;
        this.executor = executor;
    }

    // ------------------------------------------------------------------ 触发入口

    /** 知识库写入后触发（RAG 关闭时不产生任何调用）；事务内写入时延后到提交后再索引 */
    public void enqueueKnowledge(Long entryId) {
        if (entryId == null || !settings.isEnabled()) {
            return;
        }
        afterCommitOrNow(() -> submit("knowledge-" + entryId, () -> indexKnowledgeNow(entryId)));
    }

    /** 语料写入后触发（RAG 关闭时不产生任何调用）；事务内写入时延后到提交后再索引 */
    public void enqueueCorpus(Long corpusId) {
        if (corpusId == null || !settings.isEnabled()) {
            return;
        }
        afterCommitOrNow(() -> submit("corpus-" + corpusId, () -> indexCorpusNow(corpusId)));
    }

    /**
     * 事务感知入队：知识库/语料写入经常发生在业务事务内（如复核落最终结论时回馈语料）， 异步线程若在事务提交前执行 {@code findById} 会读不到新行并静默跳过索引。
     * 因此有事务同步时注册 afterCommit 回调再提交线程池；无事务上下文（单测/非事务调用）直接提交。 与 {@code ReviewService} 里通知/词挖掘的
     * afterCommit 范式保持一致。
     */
    private void afterCommitOrNow(Runnable task) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            task.run();
                        }
                    });
        } else {
            task.run();
        }
    }

    /** 删除知识库条目时清理其分块 */
    public void deleteKnowledgeChunks(Long entryId) {
        if (entryId == null) {
            return;
        }
        try {
            chunks.deleteByEntryId(entryId);
        } catch (Exception e) {
            log.warn("清理知识库分块失败 entryId={}: {}", entryId, e.getMessage());
        }
    }

    /** 手动重建单条知识库条目（无论 RAG 开关，管理员显式触发） */
    public void reindexKnowledgeEntry(Long entryId) {
        if (entryId == null) {
            return;
        }
        try {
            knowledgeEntries
                    .findById(entryId)
                    .ifPresent(
                            entry -> {
                                entry.setIndexStatus(RagIndexStatus.PENDING.name());
                                entry.setIndexError(null);
                                knowledgeEntries.save(entry);
                            });
        } catch (Exception e) {
            log.warn("重置条目索引状态失败 entryId={}: {}", entryId, e.getMessage());
        }
        enqueueForce("knowledge-" + entryId, () -> indexKnowledgeNow(entryId));
    }

    /** 手动重建：知识库全部条目（无论 RAG 开关，管理员显式触发）；返回真实入队/跳过数 */
    public EnqueueResult reindexKnowledge() {
        List<KnowledgeEntry> entries = knowledgeEntries.findAll();
        int queued = 0;
        for (KnowledgeEntry entry : entries) {
            if (enqueueForce(
                    "knowledge-" + entry.getId(), () -> indexKnowledgeNow(entry.getId()))) {
                queued++;
            }
        }
        return new EnqueueResult(queued, entries.size() - queued);
    }

    /** 手动重建：语料库全部条目；返回真实入队/跳过数 */
    public EnqueueResult reindexCorpus() {
        List<CorpusEntry> entries = corpusEntries.findAll();
        int queued = 0;
        for (CorpusEntry entry : entries) {
            if (enqueueForce("corpus-" + entry.getId(), () -> indexCorpusNow(entry.getId()))) {
                queued++;
            }
        }
        return new EnqueueResult(queued, entries.size() - queued);
    }

    /** 启动回填：RAG 开启时补齐历史数据（异步，不阻塞启动） */
    public void backfillOnStartup() {
        if (!settings.isEnabled() || !properties.getIndex().isStartupBackfill()) {
            return;
        }
        List<KnowledgeEntry> pending = new ArrayList<>();
        try {
            for (KnowledgeEntry entry :
                    knowledgeEntries.findByIndexStatusNot(RagIndexStatus.READY.name())) {
                // 文档导入但正文为空 = 解析失败/尚未解析。直接重索引会把它洗成 READY(0 块) 并抹掉
                // 失败原因（P2-12），这里跳过并由管理员重新导入/排查；手工条目的空正文仍按合法空内容处理。
                if (blankDocumentContent(entry)) {
                    log.warn("RAG 启动回填跳过正文为空的文档条目 entryId={}（解析失败或未解析，需重新导入）", entry.getId());
                    continue;
                }
                pending.add(entry);
            }
        } catch (Exception e) {
            log.warn("读取待回填知识库条目失败：{}", e.getMessage());
        }
        List<CorpusEntry> corpus = new ArrayList<>();
        try {
            corpus.addAll(corpusEntries.findByEmbeddingTextIsNull());
        } catch (Exception e) {
            log.warn("读取待回填语料失败：{}", e.getMessage());
        }
        if (pending.isEmpty() && corpus.isEmpty()) {
            log.info("RAG 启动回填：没有待索引数据");
            return;
        }
        log.info("RAG 启动回填：知识库 {} 条、语料 {} 条（异步执行）", pending.size(), corpus.size());
        int skipped = 0;
        for (KnowledgeEntry entry : pending) {
            if (!enqueueForce(
                    "knowledge-" + entry.getId(), () -> indexKnowledgeNow(entry.getId()))) {
                skipped++;
            }
        }
        for (CorpusEntry entry : corpus) {
            if (!enqueueForce("corpus-" + entry.getId(), () -> indexCorpusNow(entry.getId()))) {
                skipped++;
            }
        }
        if (skipped > 0) {
            log.warn("RAG 启动回填：{} 条因索引队列已满被跳过（可稍后在设置页手动重建）", skipped);
        }
    }

    /** 文档导入但正文为空：解析失败/未解析，回填与手动重建都不应把它洗成 READY(0 块) */
    private static boolean blankDocumentContent(KnowledgeEntry entry) {
        return "DOCUMENT".equals(entry.getSourceType()) && !StringUtils.hasText(entry.getContent());
    }

    // ------------------------------------------------------------------ 知识库

    /** 同步索引一个知识库条目（异步线程/文档解析线程调用） */
    public void indexKnowledgeNow(Long entryId) {
        if (!markRunning("knowledge-" + entryId)) {
            return;
        }
        try {
            doIndexKnowledge(entryId);
        } catch (Exception e) {
            log.warn("知识库索引失败 entryId={}: {}", entryId, e.getMessage());
            markFailed(entryId, e.getMessage());
        } finally {
            clearRunning("knowledge-" + entryId);
        }
    }

    private void doIndexKnowledge(Long entryId) {
        KnowledgeEntry entry = knowledgeEntries.findById(entryId).orElse(null);
        if (entry == null) {
            log.warn("知识库条目 {} 已不存在，跳过索引（可能已被删除，或事务尚未提交）", entryId);
            return;
        }
        String title = entry.getTitle() == null ? "" : entry.getTitle().trim();
        String category = entry.getCategory() == null ? "" : entry.getCategory().trim();
        String content = entry.getContent() == null ? "" : entry.getContent();
        String fingerprint = sha256(title + "\n" + category + "\n" + content);
        if (RagIndexStatus.READY.name().equals(entry.getIndexStatus())
                && fingerprint.equals(entry.getEmbeddingHash())
                && entry.getChunkCount() > 0) {
            return;
        }

        entry.setIndexStatus(RagIndexStatus.INDEXING.name());
        entry.setIndexError(null);
        knowledgeEntries.save(entry);

        List<String> pieces =
                TextChunker.split(
                        content,
                        properties.effectiveChunkChars(),
                        properties.effectiveChunkOverlap());
        if (pieces.size() > MAX_CHUNKS_PER_ENTRY) {
            log.warn(
                    "知识库条目 {} 分块数 {} 超过上限 {}，仅索引前 {} 块",
                    entryId,
                    pieces.size(),
                    MAX_CHUNKS_PER_ENTRY,
                    MAX_CHUNKS_PER_ENTRY);
            pieces = pieces.subList(0, MAX_CHUNKS_PER_ENTRY);
        }
        if (pieces.isEmpty()) {
            // 空白正文分两种：手工条目允许合法为空（READY + 0 块）；文档导入为空多半是解析失败，
            // 若直接置 READY 会抹掉失败原因、页面显示正常却检索不到内容。
            if (blankDocumentContent(entry)) {
                markFailed(entryId, "文档正文为空（解析失败或尚未解析），请重新导入或检查原始文件");
                return;
            }
            replaceChunks(entryId, List.of());
            markReady(entry, fingerprint, 0);
            return;
        }

        List<String> embedTexts = new ArrayList<>(pieces.size());
        for (String piece : pieces) {
            embedTexts.add(embedText(title, category, piece));
        }
        List<float[]> vectors;
        try {
            vectors =
                    embeddingClient.embed(embedTexts, null, AiInvocationService.STAGE_EMBED_INDEX);
        } catch (RuntimeException e) {
            markFailed(entryId, e.getMessage());
            return;
        }
        if (vectors.size() != pieces.size()) {
            markFailed(entryId, "embedding 返回条数不匹配：期望 " + pieces.size() + "，实际 " + vectors.size());
            return;
        }
        List<KnowledgeChunk> newChunks = new ArrayList<>(pieces.size());
        LocalDateTime now = LocalDateTime.now();
        for (int i = 0; i < pieces.size(); i++) {
            KnowledgeChunk chunk = new KnowledgeChunk();
            chunk.setEntryId(entryId);
            chunk.setChunkIndex(i);
            chunk.setContent(pieces.get(i));
            chunk.setContentHash(sha256(pieces.get(i)));
            chunk.setEmbeddingText(toPgVector(vectors.get(i)));
            chunk.setEmbeddingModel(embeddingSettings.resolve().model());
            chunk.setEmbeddingDim(properties.effectiveDimensions());
            chunk.setCreatedAt(now);
            chunk.setUpdatedAt(now);
            newChunks.add(chunk);
        }
        replaceChunks(entryId, newChunks);
        markReady(entry, fingerprint, newChunks.size());
        log.info("知识库条目 {} 索引完成：{} 块", entryId, newChunks.size());
    }

    private void replaceChunks(Long entryId, List<KnowledgeChunk> newChunks) {
        chunks.deleteByEntryId(entryId);
        if (!newChunks.isEmpty()) {
            chunks.saveAll(newChunks);
        }
    }

    private void markReady(KnowledgeEntry entry, String fingerprint, int chunkCount) {
        entry.setIndexStatus(RagIndexStatus.READY.name());
        entry.setIndexError(null);
        entry.setEmbeddingHash(fingerprint);
        entry.setChunkCount(chunkCount);
        entry.setIndexedAt(LocalDateTime.now());
        knowledgeEntries.save(entry);
    }

    private void markFailed(Long entryId, String error) {
        try {
            KnowledgeEntry entry = knowledgeEntries.findById(entryId).orElse(null);
            if (entry == null) {
                return;
            }
            entry.setIndexStatus(RagIndexStatus.FAILED.name());
            entry.setIndexError(truncate(error, 1000));
            knowledgeEntries.save(entry);
        } catch (Exception e) {
            log.warn("标记知识库索引失败状态时出错 entryId={}: {}", entryId, e.getMessage());
        }
    }

    private static String embedText(String title, String category, String chunk) {
        StringBuilder sb = new StringBuilder();
        if (StringUtils.hasText(title)) {
            sb.append(title).append('\n');
        }
        if (StringUtils.hasText(category)) {
            sb.append(category).append('\n');
        }
        sb.append(chunk);
        return sb.toString();
    }

    // ------------------------------------------------------------------ 语料库

    public void indexCorpusNow(Long corpusId) {
        if (!markRunning("corpus-" + corpusId)) {
            return;
        }
        try {
            doIndexCorpus(corpusId);
        } catch (Exception e) {
            log.warn("语料索引失败 corpusId={}: {}", corpusId, e.getMessage());
        } finally {
            clearRunning("corpus-" + corpusId);
        }
    }

    private void doIndexCorpus(Long corpusId) {
        CorpusEntry entry = corpusEntries.findById(corpusId).orElse(null);
        if (entry == null) {
            log.warn("语料 {} 已不存在，跳过索引（可能已被删除，或事务尚未提交）", corpusId);
            return;
        }
        if (!StringUtils.hasText(entry.getTranscript())) {
            return;
        }
        String transcript = entry.getTranscript();
        String hash = sha256(transcript);
        if (hash.equals(entry.getEmbeddingHash())
                && StringUtils.hasText(entry.getEmbeddingText())) {
            return;
        }
        String text =
                transcript.length() > properties.effectiveCorpusMaxChars()
                        ? transcript.substring(0, properties.effectiveCorpusMaxChars())
                        : transcript;
        List<float[]> vectors =
                embeddingClient.embed(List.of(text), null, AiInvocationService.STAGE_EMBED_INDEX);
        if (vectors.isEmpty()) {
            throw new RagEmbeddingException("语料 embedding 返回为空");
        }
        entry.setEmbeddingText(toPgVector(vectors.get(0)));
        entry.setEmbeddingModel(embeddingSettings.resolve().model());
        entry.setEmbeddingHash(hash);
        entry.setEmbeddingUpdatedAt(LocalDateTime.now());
        corpusEntries.save(entry);
        log.info("语料 {} 向量化完成", corpusId);
    }

    // ------------------------------------------------------------------ 工具

    /** 转成 pgvector 的文本表示：[0.1,0.2,...] */
    static String toPgVector(float[] vector) {
        StringBuilder sb = new StringBuilder(vector.length * 12 + 2);
        sb.append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(Float.toString(vector[i]));
        }
        return sb.append(']').toString();
    }

    static String sha256(String text) {
        return sha256(text.getBytes(StandardCharsets.UTF_8));
    }

    static String sha256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /**
     * 提交索引任务到线程池。
     *
     * @return true = 已接收；false = 队列满被拒绝（AbortPolicy 抛 RejectedExecutionException），
     *     调用方据此统计真实入队数（P3-3），不再把丢弃虚报为成功
     */
    private boolean submit(String key, Runnable task) {
        try {
            executor.execute(
                    () -> {
                        try {
                            task.run();
                        } catch (Exception e) {
                            log.warn("RAG 索引任务失败 {}: {}", key, e.getMessage());
                        }
                    });
            return true;
        } catch (RejectedExecutionException e) {
            log.warn("RAG 索引队列已满，跳过 {}（可稍后手动重建）", key);
            return false;
        }
    }

    /** 手动重建不检查 RAG 开关（显式管理动作）；返回是否真正入队 */
    private boolean enqueueForce(String key, Runnable task) {
        return submit(key, task);
    }

    private boolean markRunning(String key) {
        return running.putIfAbsent(key, Boolean.TRUE) == null;
    }

    private void clearRunning(String key) {
        running.remove(key);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "未知错误";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
