package com.echolint.service;

import com.echolint.config.ScreenModelProperties;
import com.echolint.dfa.DfaHit;
import com.echolint.domain.ViolationType;
import com.echolint.exception.BizException;
import com.echolint.service.AiScreenResult.CorpusExample;
import com.echolint.service.rag.RagContext;
import com.echolint.service.rag.RagReference;
import com.echolint.service.rag.RagRetrievalService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * AI 语义复筛：在 DFA 命中的基础上，结合 few-shot 语料判断是否构成真实违规。
 *
 * <p>具体用哪个模型由 {@link ScreenModelService} 在运行时决定（DeepSeek / 阿里云百炼 / 自建网关均可）， 本类只负责组装提示词、分块调用与解析结论。
 *
 * <p>Batch 2b-2 的两条硬约束：
 *
 * <ul>
 *   <li><b>全文分块</b>：转写文本超过 {@code app.screen-models.chunk-chars} 时带重叠切块逐块复核，
 *       任一块违规即整体违规（取最高置信度），每块调用都写 ai_invocations 留痕； 分块数超过 {@code max-chunks} 时截断并日志注明，避免长录音丢后半段。
 *   <li><b>严格解析</b>：返回必须是含布尔 {@code violation} 字段的 JSON 对象；缺失/非布尔/非 JSON 一律按调用失败处理（抛
 *       BizException，由流水线降级转人工）； {@code violation=true} 但置信度低于 {@code min-confidence} 时标记
 *       degraded，同样不得自动放行。
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SemanticScreeningService {

    /** 分块重叠上限：约 10%，最多 500 字符（保证跨块边界的句子有上下文） */
    private static final int MAX_OVERLAP_CHARS = 500;

    private static final int MIN_OVERLAP_CHARS = 1;

    /** OpenAI 兼容响应里表示「输出被 max_tokens 截断」的 finish_reason */
    static final String FINISH_REASON_LENGTH = "length";

    private final ScreenModelProperties screenModelProperties;
    private final ScreenModelService screenModelService;
    private final AiInvocationService invocations;
    private final ObjectMapper objectMapper;
    private final AiModelCaller aiModelCaller;
    private final AiQuotaService quotaService;
    private final ScreeningPromptSupport promptSupport;
    private final RagRetrievalService ragRetrievalService;

    /**
     * @param recordingId 用于 AI 调用留痕
     * @param transcript 全文转写（DFA 命中偏移即基于此文本）
     * @param segmentsJson 转写分段 JSON（双声道时带 speaker/channel，用于给模型补充说话人信息）
     */
    public AiScreenResult screen(
            Long recordingId,
            String transcript,
            String segmentsJson,
            List<DfaHit> hits,
            List<CorpusExample> examples) {
        return screenWithTrace(recordingId, transcript, segmentsJson, hits, examples).result();
    }

    /**
     * 复筛并返回 RAG 检索轨迹：未开启 RAG 时 trace.status=disabled；检索失败时 status=degraded， 但不影响复筛结论本身（已有的降级路径不变）。
     *
     * <p>安全兜底：分块数超过 {@code max-chunks} 导致尾部未复核、或转写文本命中明显提示词注入模式时， 若模型给出「合规」结论会强制标记 degraded
     * 转人工（绝不在没看全/可能被注入的文本上自动放行）； 违规结论保持原样（本就需要人工复核），仅把说明追加进 reason。
     */
    public ScreeningOutcome screenWithTrace(
            Long recordingId,
            String transcript,
            String segmentsJson,
            List<DfaHit> hits,
            List<CorpusExample> examples) {
        // 先取模型（A/B 分流；不校验密钥）并组装请求体，再校验密钥：
        // 这样即便因缺密钥失败，留痕里也能看到「本该发出去什么」，排查时和看报错同样重要
        final ScreenModelService.ScreeningModel selection = screenModelService.activeForScreening();
        final ScreenModelService.ActiveModel model = selection.model();
        final String screenVariant = selection.variant();
        String auditText = annotateBySpeaker(transcript, segmentsJson);
        int chunkChars = screenModelProperties.effectiveChunkChars();
        int maxChunks = screenModelProperties.effectiveMaxChunks();
        // 输出侧注入兜底：命中明显注入模式时，合规结论一律转人工（窄正则，避免误伤正常语料）
        boolean injectionSuspected =
                ScreeningPromptSupport.containsSuspiciousInstruction(auditText);
        if (injectionSuspected) {
            log.warn("[{}] 转写文本命中疑似提示词注入模式，若模型判定合规将强制转人工", recordingId);
        }

        List<TextChunk> chunkInfos = splitChunksWithOffsets(auditText, chunkChars, maxChunks + 1);
        // limit = maxChunks + 1：检测到 > maxChunks 即说明被截断（真实总块数可能更多）
        int detectedChunks = chunkInfos.size();
        boolean truncated = detectedChunks > maxChunks;
        if (truncated) {
            log.warn(
                    "[{}] 转写文本 {} 字符，分块数超过上限 {}（每块 {} 字符），仅复核前 {} 块，其余文本截断；" + "尾部未复核将强制转人工",
                    recordingId,
                    auditText.length(),
                    maxChunks,
                    chunkChars,
                    maxChunks);
            chunkInfos = new ArrayList<>(chunkInfos.subList(0, maxChunks));
        }
        if (chunkInfos.isEmpty()) {
            chunkInfos = List.of(new TextChunk(0, 0, ""));
        }
        if (chunkInfos.size() > 1) {
            log.info(
                    "[{}] 转写文本 {} 字符，分 {} 块复核（每块上限 {} 字符，重叠 {} 字符）",
                    recordingId,
                    auditText.length(),
                    chunkInfos.size(),
                    chunkChars,
                    overlapChars(chunkChars));
        }

        List<String> chunkTexts = new ArrayList<>(chunkInfos.size());
        for (TextChunk chunkInfo : chunkInfos) {
            chunkTexts.add(chunkInfo.text());
        }
        // 命中词按块过滤：hits 的 offset 基于原始 transcript，而 annotateBySpeaker 会加【说话人】前缀
        // 改变长度——有说话人前缀时退化用「词是否出现在该块」的窄匹配，避免错配到别的段落。
        boolean offsetsValid = transcript != null && auditText.equals(transcript);
        // 批量 query embedding：RAG 开启时所有分块合并为一次 embedding 调用（内部按 batch-size 分批），
        // 避免长录音每块一次 HTTP 往返（P3-2）。
        List<RagContext> ragContexts =
                ragRetrievalService.knowledgeContexts(chunkTexts, recordingId);
        if (ragContexts.size() != chunkTexts.size()) {
            log.warn(
                    "[{}] RAG 批量检索返回 {} 个上下文（期望 {}），按未检索处理",
                    recordingId,
                    ragContexts.size(),
                    chunkTexts.size());
            ragContexts = Collections.nCopies(chunkTexts.size(), RagContext.empty());
        }
        List<ChunkResult> results = new ArrayList<>(chunkTexts.size());
        for (int i = 0; i < chunkTexts.size(); i++) {
            List<DfaHit> chunkHits = hitsForChunk(hits, chunkInfos.get(i), offsetsValid);
            results.add(
                    screenChunk(
                            recordingId,
                            model,
                            screenVariant,
                            chunkTexts.get(i),
                            chunkHits,
                            examples,
                            ragContexts.get(i).references(),
                            i + 1,
                            chunkTexts.size()));
        }
        AiScreenResult aggregateResult =
                aggregate(model, screenVariant, results, chunkTexts.size());
        List<String> safetyNotes = new ArrayList<>(2);
        if (truncated) {
            safetyNotes.add(
                    String.format(
                            "分块数超过上限（上限 %d 块，已检测到至少 %d 块，仅复核前 %d 块），" + "尾部内容未复核，转人工",
                            maxChunks, detectedChunks, chunkTexts.size()));
        }
        if (injectionSuspected) {
            safetyNotes.add("转写文本疑似包含提示词注入指令，模型结论可信度存疑，转人工");
        }
        AiScreenResult finalResult = applySafetyNotes(aggregateResult, safetyNotes);
        return new ScreeningOutcome(finalResult, buildTrace(ragContexts));
    }

    /** 安全性兜底：截断/注入等「数据不完整或不可信」场景下，合规结论强制降级（转人工）， 违规结论保持原判定（同样转人工），并把说明追加进 reason，便于人工复核时了解上下文。 */
    private static AiScreenResult applySafetyNotes(AiScreenResult result, List<String> notes) {
        if (notes == null || notes.isEmpty()) {
            return result;
        }
        String note = String.join("；", notes);
        String reason = StringUtils.hasText(result.reason()) ? result.reason() + "；" + note : note;
        if (result.violation()) {
            return rebuildResult(result, reason, result.degraded(), result.degradedReason());
        }
        String degradedReason =
                result.degraded() && StringUtils.hasText(result.degradedReason())
                        ? result.degradedReason() + "；" + note
                        : note;
        return rebuildResult(result, reason, true, degradedReason);
    }

    /** 重建结果对象（AiScreenResult 为不可变 record，只改 reason/degraded 相关字段）。 */
    private static AiScreenResult rebuildResult(
            AiScreenResult result, String reason, boolean degraded, String degradedReason) {
        return new AiScreenResult(
                result.violation(),
                result.violationType(),
                result.typeLabel(),
                reason,
                result.confidence(),
                result.targetSentence(),
                result.providerId(),
                result.providerLabel(),
                result.model(),
                degraded,
                degradedReason,
                result.screenVariant());
    }

    /**
     * 单块调用：请求/回复逐块留痕，任何异常都向上抛（由流水线降级转人工）。
     *
     * <p>成本/配额记账（Batch 6 收尾）：已计费的调用即使解析失败/被截断也把 usage 落账； 配额校验改「原子预留 + finally 释放」；输出被 {@code
     * max_tokens} 截断时以 2× 上限重试一次。
     */
    private ChunkResult screenChunk(
            Long recordingId,
            ScreenModelService.ActiveModel model,
            String screenVariant,
            String chunk,
            List<DfaHit> hits,
            List<CorpusExample> examples,
            List<RagReference> references,
            int index,
            int total) {
        String requestJson =
                promptSupport.buildRequestBody(
                        model,
                        chunk,
                        hits,
                        examples,
                        references,
                        index,
                        total,
                        screenModelProperties.getTemperature());
        long start = System.currentTimeMillis();
        AiUsage usage = null;
        try {
            screenModelService.requireUsable(model);
            // 配额/限流在每次真正调用前校验并原子预留；try-with-resources 保证所有路径都释放预留
            try (AiQuotaService.Reservation ignored = quotaService.checkBeforeCall(model)) {
                JsonNode resp = aiModelCaller.call(model, requestJson, "语义复筛");
                usage = AiUsage.extract(resp).pricedWith(model);
                if (FINISH_REASON_LENGTH.equals(ScreenModelService.finishReasonOf(resp))) {
                    // 输出被 max_tokens 截断：JSON 通常不完整。首跳已计费先落失败账，再 2× 上限重试一次
                    int retryMaxTokens = nextMaxTokens(model);
                    log.warn(
                            "[{}] AI 复筛第 {} 块输出被截断（finish_reason=length），以 max_tokens={} 重试一次",
                            model.display(),
                            index,
                            retryMaxTokens);
                    invocations.recordFailure(
                            recordingId,
                            AiInvocationService.STAGE_SCREEN,
                            model,
                            requestJson,
                            "输出被截断（finish_reason=length），以 2× max_tokens 重试",
                            elapsed(start),
                            usage);
                    requestJson =
                            promptSupport.buildRequestBody(
                                    model,
                                    chunk,
                                    hits,
                                    examples,
                                    references,
                                    index,
                                    total,
                                    screenModelProperties.getTemperature(),
                                    retryMaxTokens);
                    resp = aiModelCaller.call(model, requestJson, "语义复筛(截断重试)");
                    usage = AiUsage.extract(resp).pricedWith(model);
                    if (FINISH_REASON_LENGTH.equals(ScreenModelService.finishReasonOf(resp))) {
                        throw new BizException(
                                "AI 复筛输出被截断（finish_reason=length），无法判定，已降级人工复核 ["
                                        + model.display()
                                        + "]",
                                HttpStatus.INTERNAL_SERVER_ERROR);
                    }
                }
                String content = ScreenModelService.contentOf(resp);
                AiScreenResult result =
                        promptSupport.parseResult(content, model, screenVariant, index);
                invocations.recordSuccess(
                        recordingId,
                        AiInvocationService.STAGE_SCREEN,
                        model,
                        requestJson,
                        content,
                        elapsed(start),
                        usage);
                return new ChunkResult(index, result);
            }
        } catch (RuntimeException e) {
            // 模型已返回但解析失败（或截断重试仍失败）时 usage 非空：必须落账，否则成本看板低估、配额被绕过
            invocations.recordFailure(
                    recordingId,
                    AiInvocationService.STAGE_SCREEN,
                    model,
                    requestJson,
                    e.getMessage(),
                    elapsed(start),
                    usage);
            throw e;
        }
    }

    /** 截断重试的 max_tokens：取配置值 ×2（缺省按 2048），上限 8192，避免无限放大成本。 */
    private static int nextMaxTokens(ScreenModelService.ActiveModel model) {
        int current =
                model.maxTokens() == null || model.maxTokens() <= 0 ? 2048 : model.maxTokens();
        return Math.min(8192, current * 2);
    }

    /** 汇总各块结论：任一块违规即整体违规（取最高置信度的块作为结论），否则整体合规。 违规但置信度低于阈值 → 标记 degraded（流水线会转人工，不会自动放行）。 */
    private AiScreenResult aggregate(
            ScreenModelService.ActiveModel model,
            String screenVariant,
            List<ChunkResult> results,
            int chunkCount) {
        ChunkResult highestViolation = null;
        double maxCompliantConfidence = 0;
        String firstReason = null;
        for (ChunkResult chunk : results) {
            AiScreenResult r = chunk.result();
            if (r.violation()) {
                if (highestViolation == null
                        || r.confidence() > highestViolation.result().confidence()) {
                    highestViolation = chunk;
                }
            } else {
                maxCompliantConfidence = Math.max(maxCompliantConfidence, r.confidence());
                if (firstReason == null && StringUtils.hasText(r.reason())) {
                    firstReason = r.reason();
                }
            }
        }

        if (highestViolation == null) {
            AiScreenResult first = results.get(0).result();
            return new AiScreenResult(
                    false,
                    ViolationType.NONE,
                    ViolationType.NONE.getLabel(),
                    chunkCount == 1
                            ? (firstReason == null ? "" : firstReason)
                            : "分块复核 " + chunkCount + " 块均判定合规",
                    chunkCount == 1 ? first.confidence() : maxCompliantConfidence,
                    "",
                    model.providerId(),
                    model.providerLabel(),
                    model.model(),
                    false,
                    null,
                    screenVariant);
        }

        AiScreenResult top = highestViolation.result();
        String reason =
                chunkCount == 1
                        ? top.reason()
                        : "分块复核第 "
                                + highestViolation.index()
                                + "/"
                                + chunkCount
                                + " 块判定违规："
                                + top.reason();
        AiScreenResult merged =
                new AiScreenResult(
                        true,
                        top.violationType(),
                        top.typeLabel(),
                        reason,
                        top.confidence(),
                        top.targetSentence(),
                        model.providerId(),
                        model.providerLabel(),
                        model.model(),
                        false,
                        null,
                        screenVariant);
        double minConfidence = screenModelProperties.effectiveMinConfidence();
        if (merged.confidence() < minConfidence) {
            String why =
                    String.format(
                            "违规置信度 %.2f 低于阈值 %.2f，降级人工复核", merged.confidence(), minConfidence);
            log.warn(
                    "[{}] AI 判定违规但置信度不足（confidence={}, minConfidence={}），转人工复检",
                    model.display(),
                    merged.confidence(),
                    minConfidence);
            return merged.withDegraded(why);
        }
        return merged;
    }

    private static long elapsed(long start) {
        return System.currentTimeMillis() - start;
    }

    // ------------------------------------------------------------------ 分块

    /**
     * 按字符数切块（带重叠）。单块文本不超过 {@code chunkChars} 时原样返回一块。
     *
     * <p>切点优先落在换行/空格上，避免把句子从中间切开；{@code limit} 为最多收集的块数 （调用方传 maxChunks+1 以便判断是否发生截断）。
     */
    static List<String> splitChunks(String text, int chunkChars, int limit) {
        List<TextChunk> chunks = splitChunksWithOffsets(text, chunkChars, limit);
        List<String> out = new ArrayList<>(chunks.size());
        for (TextChunk chunk : chunks) {
            out.add(chunk.text());
        }
        return out;
    }

    /**
     * 按字符数切块并保留每块在原文本中的半开区间 {@code [start, end)}，用于按块过滤 DFA 命中词。
     *
     * <p>切块规则与 {@link #splitChunks} 完全一致（同一 boundary/overlap 逻辑）， 相邻块因重叠会在区间上重叠，调用方按需去重。
     */
    static List<TextChunk> splitChunksWithOffsets(String text, int chunkChars, int limit) {
        String value = text == null ? "" : text;
        int chunkSize = Math.max(1, chunkChars);
        int maxChunks = Math.max(1, limit);
        if (value.length() <= chunkSize) {
            return List.of(new TextChunk(0, value.length(), value));
        }
        int overlap = overlapChars(chunkSize);
        List<TextChunk> chunks = new ArrayList<>();
        int start = 0;
        while (start < value.length() && chunks.size() < maxChunks) {
            int end = Math.min(value.length(), start + chunkSize);
            if (end < value.length()) {
                end = adjustBoundary(value, start, end, chunkSize);
            }
            chunks.add(new TextChunk(start, end, value.substring(start, end)));
            if (end >= value.length()) {
                break;
            }
            start = Math.max(end - overlap, start + 1);
        }
        return chunks;
    }

    /**
     * 过滤出落在该块范围内的 DFA 命中词。
     *
     * @param offsetsValid true 表示 hits 的 offset 与块文本一致（无说话人前缀）；false 时退化为
     *     「命中词是否出现在该块文本中」（annotateBySpeaker 改写长度后的兜底）
     */
    static List<DfaHit> hitsForChunk(List<DfaHit> hits, TextChunk chunk, boolean offsetsValid) {
        if (hits == null || hits.isEmpty() || chunk == null) {
            return List.of();
        }
        if (offsetsValid) {
            List<DfaHit> out = new ArrayList<>();
            for (DfaHit hit : hits) {
                if (hit.start() >= chunk.start() && hit.end() <= chunk.end()) {
                    out.add(hit);
                }
            }
            return out;
        }
        List<DfaHit> out = new ArrayList<>();
        for (DfaHit hit : hits) {
            if (hit.word() != null && !hit.word().isEmpty() && chunk.text().contains(hit.word())) {
                out.add(hit);
            }
        }
        return out;
    }

    /** 带原文偏移的分块（start/end 为半开区间） */
    record TextChunk(int start, int end, String text) {}

    /** 重叠字符数：约 10%，最多 500，让跨块边界的句子两边都有上下文 */
    static int overlapChars(int chunkChars) {
        return Math.max(MIN_OVERLAP_CHARS, Math.min(MAX_OVERLAP_CHARS, chunkChars / 10));
    }

    /** 把切点回退到最近的换行/空格；回退幅度不超过半块，避免块过短 */
    private static int adjustBoundary(String text, int start, int end, int chunkSize) {
        int floor = start + chunkSize / 2;
        int newline = text.lastIndexOf('\n', end - 1);
        if (newline > floor) {
            return newline;
        }
        int space = text.lastIndexOf(' ', end - 1);
        if (space > floor) {
            return space;
        }
        return end;
    }

    /** 单块复核结果（index 为 1 基块号，用于日志/理由里标注是第几块违规） */
    private record ChunkResult(int index, AiScreenResult result) {}

    /** 双声道录音按 【说话人】文本 渲染，帮助模型区分说话人（坐席违规与客户辱骂性质不同） */
    private String annotateBySpeaker(String transcript, String segmentsJson) {
        String plain = transcript == null ? "" : transcript;
        if (segmentsJson == null || segmentsJson.isBlank() || "[]".equals(segmentsJson.trim())) {
            return plain;
        }
        try {
            JsonNode node = objectMapper.readTree(segmentsJson);
            if (!node.isArray() || node.isEmpty()) {
                return plain;
            }
            StringBuilder sb = new StringBuilder();
            boolean hasSpeaker = false;
            for (JsonNode seg : node) {
                String speaker = seg.path("speaker").asText("");
                String text = seg.path("text").asText("").trim();
                if (text.isEmpty()) {
                    continue;
                }
                if (!speaker.isBlank()) {
                    hasSpeaker = true;
                    sb.append("【").append(speaker).append("】").append(text).append('\n');
                } else {
                    sb.append(text).append(' ');
                }
            }
            return hasSpeaker ? sb.toString().trim() : plain;
        } catch (Exception e) {
            return plain;
        }
    }

    // ------------------------------------------------------------------ RAG 轨迹

    /**
     * 汇总各分块的检索轨迹：任一分块降级即整体 degraded；取并集（按 chunkId 去重，最多 10 条）， 便于 aiResultJson / 日志回溯「这次复筛参考了哪些知识」。
     */
    private RagTrace buildTrace(List<RagContext> contexts) {
        boolean enabled = contexts.stream().anyMatch(RagContext::enabled);
        if (!enabled) {
            return new RagTrace(false, RagContext.STATUS_DISABLED, List.of(), null);
        }
        boolean degraded =
                contexts.stream().anyMatch(c -> RagContext.STATUS_DEGRADED.equals(c.status()));
        String error =
                contexts.stream()
                        .map(RagContext::error)
                        .filter(Objects::nonNull)
                        .findFirst()
                        .orElse(null);
        boolean hasRefs = contexts.stream().anyMatch(RagContext::hasReferences);
        String status =
                degraded
                        ? RagContext.STATUS_DEGRADED
                        : (hasRefs ? RagContext.STATUS_OK : RagContext.STATUS_EMPTY);
        Map<Long, RagReference> unique = new LinkedHashMap<>();
        for (RagContext context : contexts) {
            for (RagReference reference : context.references()) {
                unique.putIfAbsent(reference.chunkId(), reference);
            }
        }
        List<RagReferenceView> views =
                unique.values().stream()
                        .limit(10)
                        .map(r -> new RagReferenceView(r.chunkId(), r.title(), r.score()))
                        .toList();
        return new RagTrace(true, status, views, error);
    }

    /** 复筛结果 + RAG 检索轨迹 */
    public record ScreeningOutcome(AiScreenResult result, RagTrace trace) {}

    /** 检索轨迹（只存摘要，完整检索上下文已包含在 ai_invocations.request_json 的提示词里） */
    public record RagTrace(
            boolean enabled, String status, List<RagReferenceView> references, String error) {}

    /** 引用摘要（标题 + 相似度），供前端/审计展示 */
    public record RagReferenceView(long chunkId, String title, double score) {}
}
