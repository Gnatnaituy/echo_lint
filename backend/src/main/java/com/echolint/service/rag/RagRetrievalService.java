package com.echolint.service.rag;

import com.echolint.config.RagProperties;
import com.echolint.domain.CorpusLabel;
import com.echolint.entity.CorpusEntry;
import com.echolint.exception.BizException;
import com.echolint.repository.CorpusEntryRepository;
import com.echolint.service.AiScreenResult.CorpusExample;
import com.echolint.service.AiInvocationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * RAG 检索服务（Batch 6，Phase A/B）。
 *
 * <ul>
 *   <li><b>知识库</b>：用 query embedding 在 {@code knowledge_chunks} 上做 pgvector 余弦 top-k，
 *       过滤相似度阈值并按字符预算裁剪；失败一律降级为空上下文，绝不影响复筛。</li>
 *   <li><b>语料库</b>：为 Phase A 从人工复核语料中按相似度取 few-shot（违规/合规各半），
 *       不足时用最新样本补齐；RAG 关闭或 embedding 不可用时回退旧的「最新 N 条」逻辑。</li>
 * </ul>
 *
 * <p>知识库检索 SQL 使用表达式 cast：{@code embedding_text::vector(N) <=> CAST(? AS vector)}，
 * 与 V2 迁移里的 HNSW 表达式索引保持一致。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagRetrievalService {

    /** query 送 embedding 前的最大字符数：控制 token 成本，也避免超过模型输入上限 */
    static final int MAX_QUERY_CHARS = 4000;

    /** few-shot 展示的转写文本上限（与旧逻辑保持一致） */
    static final int CORPUS_EXAMPLE_CHARS = 400;

    private final JdbcTemplate jdbcTemplate;
    private final RagEmbeddingClient embeddingClient;
    private final RagSettingsService settings;
    private final RagProperties properties;
    private final CorpusEntryRepository corpusEntries;

    // ------------------------------------------------------------------ 知识库

    /** 复筛用：按当前开关检索知识片段（关闭时直接返回 disabled，不发起任何调用） */
    public RagContext knowledgeContext(String query, Long recordingId) {
        return knowledgeContext(query, recordingId, false);
    }

    /**
     * @param force true 表示忽略 RAG 总开关（评测/检索预览等显式场景）
     */
    public RagContext knowledgeContext(String query, Long recordingId, boolean force) {
        if (!force && !settings.isEnabled()) {
            return RagContext.disabled();
        }
        if (!StringUtils.hasText(query)) {
            return RagContext.empty();
        }
        float[] vector;
        try {
            vector = embeddingClient.embedQuery(truncate(query, MAX_QUERY_CHARS), recordingId);
        } catch (RuntimeException e) {
            log.warn("知识库检索降级（embedding 失败）：{}", e.getMessage());
            return RagContext.degraded(e.getMessage());
        }
        List<RagReference> candidates;
        try {
            RagSettingsService.Snapshot snapshot = settings.current();
            candidates = queryKnowledge(toPgVector(vector), properties.effectiveDimensions(),
                    Math.min(60, Math.max(snapshot.topK() * 3, snapshot.topK())));
        } catch (RuntimeException e) {
            log.warn("知识库检索降级（查询失败）：{}", e.getMessage());
            return RagContext.degraded(e.getMessage());
        }
        RagSettingsService.Snapshot snapshot = settings.current();
        double minScore = snapshot.minScore();
        int maxChars = snapshot.maxContextChars();
        int topK = snapshot.topK();
        List<RagReference> picked = new ArrayList<>();
        int used = 0;
        for (RagReference ref : candidates) {
            if (picked.size() >= topK) {
                break;
            }
            if (ref.score() < minScore) {
                continue;
            }
            int cost = ref.content() == null ? 0 : ref.content().length();
            if (used + cost > maxChars && !picked.isEmpty()) {
                break;
            }
            picked.add(ref);
            used += cost;
        }
        return picked.isEmpty() ? RagContext.empty() : RagContext.ok(picked);
    }

    /** 检索预览（管理端显式调用；不检查 RAG 开关，但要求 embedding 可用） */
    public List<RagReference> search(String query, int topK) {
        if (!StringUtils.hasText(query)) {
            throw new BizException("检索内容不能为空", HttpStatus.BAD_REQUEST);
        }
        int limit = Math.min(20, Math.max(1, topK));
        float[] vector;
        try {
            vector = embeddingClient.embedQuery(truncate(query, MAX_QUERY_CHARS), null);
        } catch (RuntimeException e) {
            throw new BizException("检索失败：" + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
        try {
            return queryKnowledge(toPgVector(vector), properties.effectiveDimensions(), limit);
        } catch (RuntimeException e) {
            throw new BizException("检索失败：" + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private List<RagReference> queryKnowledge(String vectorText, int dimensions, int limit) {
        String sql = """
                SELECT c.id, c.entry_id, c.content,
                       1 - (c.embedding_text::vector(%d) <=> CAST(? AS vector)) AS score,
                       e.title, e.category
                FROM knowledge_chunks c
                JOIN knowledge_entries e ON e.id = c.entry_id
                WHERE e.enabled = TRUE
                  AND c.embedding_text IS NOT NULL
                ORDER BY c.embedding_text::vector(%d) <=> CAST(? AS vector)
                LIMIT ?
                """.formatted(dimensions, dimensions);
        return jdbcTemplate.query(sql, (rs, rowNum) -> new RagReference(
                rs.getLong("id"),
                rs.getLong("entry_id"),
                rs.getString("title"),
                rs.getString("category"),
                rs.getString("content"),
                rs.getDouble("score")), vectorText, vectorText, limit);
    }

    // ------------------------------------------------------------------ 语料 few-shot（Phase A）

    /**
     * 语义 few-shot：违规/合规各取一半；RAG 关闭、embedding 失败或结果不足时回退到最新样本。
     * 永远返回（可能为空的）可用列表，不向调用方抛异常。
     */
    public List<CorpusExample> corpusExamples(String transcript, int count) {
        int target = Math.min(20, Math.max(0, count));
        if (target == 0) {
            return List.of();
        }
        if (!settings.isEnabled()) {
            return latestExamples(target);
        }
        if (!StringUtils.hasText(transcript)) {
            return latestExamples(target);
        }
        try {
            float[] vector = embeddingClient.embedQuery(
                    truncate(transcript, MAX_QUERY_CHARS), null);
            List<CorpusExample> result = queryCorpusExamples(toPgVector(vector),
                    properties.effectiveDimensions(), target);
            if (result.size() < target) {
                result = fillWithLatest(result, target);
            }
            return result;
        } catch (RuntimeException e) {
            log.warn("语义 few-shot 降级为最新样本：{}", e.getMessage());
            return latestExamples(target);
        }
    }

    private List<CorpusExample> queryCorpusExamples(String vectorText, int dimensions, int target) {
        int violationTarget = target / 2;
        int compliantTarget = target - violationTarget;
        List<CorpusExample> picked = new ArrayList<>(target);
        Set<Long> ids = new LinkedHashSet<>();
        pickByLabel(vectorText, dimensions, CorpusLabel.VIOLATION, violationTarget, picked, ids);
        pickByLabel(vectorText, dimensions, CorpusLabel.COMPLIANT, compliantTarget, picked, ids);
        if (picked.size() < target) {
            // 某一类不足时用另一类补齐
            pickByLabel(vectorText, dimensions, CorpusLabel.VIOLATION, target - picked.size(), picked, ids);
            pickByLabel(vectorText, dimensions, CorpusLabel.COMPLIANT, target - picked.size(), picked, ids);
        }
        return picked;
    }

    private void pickByLabel(String vectorText, int dimensions, CorpusLabel label, int limit,
                             List<CorpusExample> out, Set<Long> ids) {
        if (limit <= 0) {
            return;
        }
        String sql = """
                SELECT id, label, violation_type, transcript
                FROM corpus_entries
                WHERE embedding_text IS NOT NULL AND label = ?
                ORDER BY embedding_text::vector(%d) <=> CAST(? AS vector)
                LIMIT ?
                """.formatted(dimensions);
        List<CorpusEntry> rows = jdbcTemplate.query(sql, (rs, rowNum) -> {
            CorpusEntry entry = new CorpusEntry();
            entry.setId(rs.getLong("id"));
            entry.setLabel(CorpusLabel.valueOf(rs.getString("label")));
            entry.setViolationType(rs.getString("violation_type"));
            entry.setTranscript(rs.getString("transcript"));
            return entry;
        }, label.name(), vectorText, limit);
        int added = 0;
        for (CorpusEntry row : rows) {
            if (added >= limit) {
                break;
            }
            if (!ids.add(row.getId())) {
                continue;
            }
            out.add(toExample(row));
            added++;
        }
    }

    private List<CorpusExample> fillWithLatest(List<CorpusExample> picked, int target) {
        List<CorpusExample> out = new ArrayList<>(picked);
        for (CorpusEntry entry : latestEntries(target * 2)) {
            if (out.size() >= target) {
                break;
            }
            CorpusExample example = toExample(entry);
            if (out.stream().noneMatch(e -> e.transcript().equals(example.transcript()))) {
                out.add(example);
            }
        }
        return out;
    }

    private List<CorpusExample> latestExamples(int count) {
        List<CorpusExample> out = new ArrayList<>(count);
        for (CorpusEntry entry : latestEntries(count)) {
            out.add(toExample(entry));
        }
        return out;
    }

    private List<CorpusEntry> latestEntries(int count) {
        int perLabel = Math.max(1, count / 2);
        List<CorpusEntry> out = new ArrayList<>(count);
        for (CorpusLabel label : List.of(CorpusLabel.VIOLATION, CorpusLabel.COMPLIANT)) {
            List<CorpusEntry> rows = corpusEntries.findByLabelOrderByIdDesc(label,
                    PageRequest.of(0, perLabel));
            out.addAll(rows);
        }
        return out;
    }

    private static CorpusExample toExample(CorpusEntry entry) {
        String transcript = entry.getTranscript() == null ? "" : entry.getTranscript();
        return new CorpusExample(entry.getLabel(), entry.getViolationType(),
                truncate(transcript, CORPUS_EXAMPLE_CHARS));
    }

    // ------------------------------------------------------------------ 工具

    private static String toPgVector(float[] vector) {
        return RagIndexService.toPgVector(vector);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
