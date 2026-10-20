package com.echolint.service.rag;

import com.echolint.config.RagProperties;
import com.echolint.entity.AppSetting;
import com.echolint.exception.BizException;
import com.echolint.repository.AppSettingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.Optional;

/**
 * RAG 运行时设置（Batch 6，Phase C）。
 *
 * <p>与 AI 复筛模型的切换一致：默认值来自 application.yml（{@link RagProperties}），
 * 管理员在「系统设置」页修改后写入 {@code app_settings}，重启后保持；进程内缓存快照，
 * 更新时原子替换，避免每个分块都查一次库。</p>
 *
 * <p>embedding 供应商/模型/维度仍由 yml 决定（改动需要重建 HNSW 索引，不适合运行时热切换）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagSettingsService {

    static final String KEY_ENABLED = "rag.enabled";
    static final String KEY_TOP_K = "rag.top-k";
    static final String KEY_MIN_SCORE = "rag.min-score";
    static final String KEY_MAX_CONTEXT_CHARS = "rag.max-context-chars";
    static final String KEY_CORPUS_EXAMPLES = "rag.corpus-examples";
    static final String KEY_LAST_REINDEX_AT = "rag.last-reindex-at";

    private final AppSettingRepository settings;
    private final RagProperties properties;

    private volatile Snapshot snapshot;

    /**
     * 生效快照。source=defaults 表示尚未在界面保存过，全部取 yml 默认值。
     */
    public record Snapshot(boolean enabled, int topK, double minScore, int maxContextChars,
                           int corpusExamples, String source) {
    }

    public Snapshot current() {
        Snapshot value = snapshot;
        if (value == null) {
            synchronized (this) {
                value = snapshot;
                if (value == null) {
                    value = read();
                    snapshot = value;
                }
            }
        }
        return value;
    }

    public boolean isEnabled() {
        return current().enabled();
    }

    /**
     * 更新运行时设置（null 表示保留原值）。返回最新快照。
     */
    public Snapshot update(Boolean enabled, Integer topK, Double minScore,
                           Integer maxContextChars, Integer corpusExamples) {
        Snapshot old = current();
        boolean newEnabled = enabled == null ? old.enabled() : enabled;
        int newTopK = topK == null ? old.topK() : clampTopK(topK);
        double newMinScore = minScore == null ? old.minScore() : clampMinScore(minScore);
        int newMaxContext = maxContextChars == null ? old.maxContextChars()
                : clampMaxContextChars(maxContextChars);
        int newCorpusExamples = corpusExamples == null ? old.corpusExamples()
                : clampCorpusExamples(corpusExamples);

        save(KEY_ENABLED, Boolean.toString(newEnabled));
        save(KEY_TOP_K, Integer.toString(newTopK));
        save(KEY_MIN_SCORE, Double.toString(newMinScore));
        save(KEY_MAX_CONTEXT_CHARS, Integer.toString(newMaxContext));
        save(KEY_CORPUS_EXAMPLES, Integer.toString(newCorpusExamples));
        snapshot = new Snapshot(newEnabled, newTopK, newMinScore, newMaxContext,
                newCorpusExamples, "settings");
        log.info("RAG 设置已更新：enabled={} topK={} minScore={} maxContextChars={} corpusExamples={}",
                newEnabled, newTopK, newMinScore, newMaxContext, newCorpusExamples);
        return snapshot;
    }

    /** 重建索引完成时间（界面展示用） */
    public Optional<String> lastReindexAt() {
        return settings.findById(KEY_LAST_REINDEX_AT).map(AppSetting::getSettingValue)
                .filter(StringUtils::hasText);
    }

    public void markReindexedNow() {
        save(KEY_LAST_REINDEX_AT, java.time.LocalDateTime.now().toString());
    }

    private Snapshot read() {
        boolean hasSetting = settings.findById(KEY_ENABLED).isPresent()
                || settings.findById(KEY_TOP_K).isPresent();
        boolean enabled = readBoolean(KEY_ENABLED, properties.isEnabled());
        int topK = readInt(KEY_TOP_K, properties.effectiveTopK());
        double minScore = readDouble(KEY_MIN_SCORE, properties.effectiveMinScore());
        int maxContextChars = readInt(KEY_MAX_CONTEXT_CHARS, properties.effectiveMaxContextChars());
        int corpusExamples = readInt(KEY_CORPUS_EXAMPLES,
                properties.effectiveCorpusExamples() > 0
                        ? properties.effectiveCorpusExamples()
                        : Math.max(0, properties.getCorpusExamples()));
        return new Snapshot(enabled, clampTopK(topK), clampMinScore(minScore),
                clampMaxContextChars(maxContextChars), clampCorpusExamples(corpusExamples),
                hasSetting ? "settings" : "defaults");
    }

    private boolean readBoolean(String key, boolean fallback) {
        return settings.findById(key).map(AppSetting::getSettingValue)
                .map(v -> "true".equalsIgnoreCase(v.trim()))
                .orElse(fallback);
    }

    private int readInt(String key, int fallback) {
        return settings.findById(key).map(AppSetting::getSettingValue).map(v -> {
            try {
                return Integer.parseInt(v.trim());
            } catch (NumberFormatException e) {
                return fallback;
            }
        }).orElse(fallback);
    }

    private double readDouble(String key, double fallback) {
        return settings.findById(key).map(AppSetting::getSettingValue).map(v -> {
            try {
                return Double.parseDouble(v.trim());
            } catch (NumberFormatException e) {
                return fallback;
            }
        }).orElse(fallback);
    }

    private void save(String key, String value) {
        try {
            settings.save(AppSetting.of(key, value));
        } catch (Exception e) {
            throw new BizException("RAG 设置保存失败：" + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    static int clampTopK(int value) {
        return Math.min(20, Math.max(1, value));
    }

    static double clampMinScore(double value) {
        if (Double.isNaN(value)) {
            return 0.25;
        }
        return Math.min(1.0, Math.max(0.0, value));
    }

    static int clampMaxContextChars(int value) {
        return Math.min(20_000, Math.max(200, value));
    }

    static int clampCorpusExamples(int value) {
        return Math.min(20, Math.max(0, value));
    }

    /** 兼容大小写/空值的字符串归一化（测试与迁移用） */
    static String normalize(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    }
}
