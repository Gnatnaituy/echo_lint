package com.echolint.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.echolint.config.ScreenModelProperties;
import com.echolint.dfa.DfaHit;
import com.echolint.domain.CorpusLabel;
import com.echolint.domain.ViolationType;
import com.echolint.exception.BizException;
import com.echolint.service.AiScreenResult.CorpusExample;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * AI 语义复筛：在 DFA 命中的基础上，结合 few-shot 语料判断是否构成真实违规。
 *
 * <p>具体用哪个模型由 {@link ScreenModelService} 在运行时决定（OpenAI / DeepSeek / 自建网关均可），
 * 本类只负责组装提示词、分块调用与解析结论。</p>
 *
 * <p>Batch 2b-2 的两条硬约束：</p>
 * <ul>
 *   <li><b>全文分块</b>：转写文本超过 {@code app.screen-models.chunk-chars} 时带重叠切块逐块复核，
 *       任一块违规即整体违规（取最高置信度），每块调用都写 ai_invocations 留痕；
 *       分块数超过 {@code max-chunks} 时截断并日志注明，避免长录音丢后半段。</li>
 *   <li><b>严格解析</b>：返回必须是含布尔 {@code violation} 字段的 JSON 对象；缺失/非布尔/非 JSON
 *       一律按调用失败处理（抛 BizException，由流水线降级转人工）；
 *       {@code violation=true} 但置信度低于 {@code min-confidence} 时标记 degraded，同样不得自动放行。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SemanticScreeningService {

    private static final String SYSTEM_PROMPT = """
            你是呼叫中心录音的合规审查助手。系统已用关键词词典对录音转写文本做了一轮初筛（DFA），
            命中的词只是"候选标记"，未必构成真实违规。你的任务是在上下文语境中做最终判断。

            判定为真实违规的情形（violation=true）：
            - 辱骂（INSULT）：直接辱骂、贬低、侮辱性称呼；
            - 歧视（DISCRIMINATION）：种族、性别、宗教、地域等歧视性言论；
            - 威胁（THREAT）：威胁人身、财产、名誉安全的言语；
            - 骚扰（HARASSMENT）：持续纠缠、骚扰性言语；
            - 色情骚扰（SEXUAL）：色情、性暗示、性骚扰言语；
            - 诈骗诱导（FRAUD）：欺骗、诱导转账、虚假承诺、诈骗话术；
            - 隐私泄露（PRIVACY）：索要或泄露他人敏感隐私信息。

            需要注意的"误报"情形（violation=false）：
            - 引用、转述、否认语境（如 "I would never scam you"、教客户如何防骗）；
            - 词义误用（如 "kill the ticket"、"assist" 等无攻击语义）；
            - 轻微情绪词但未针对任何人（如 "damn, this is frustrating"）；
            - 正常业务术语与词典词巧合重合。

            只输出一个 json 对象，不要输出任何其他内容，格式：
            {"violation": true或false, "violation_type": "INSULT"或"DISCRIMINATION"或"THREAT"或"HARASSMENT"或"SEXUAL"或"FRAUD"或"PRIVACY"或"OTHER"或"NONE", "reason": "简短英文理由", "confidence": 0.0到1.0的小数, "target_sentence": "违规原句，无违规则为空字符串"}

            补充说明：
            - 若转写文本带有 【说话人】 前缀（双声道双轨录音，如 【坐席】/【客户】），请注意区分是谁说的：
              坐席辱骂/威胁/歧视客户属于严重违规；客户辱骂坐席也应标记，但理由中要写清说话人。
            - target_sentence 必须是纯文本原句，不要包含 【说话人】 前缀。
            - 若提示词说明这是全文的第 N 块，只需判定该块内容，不要臆测其它块。
            """;

    /** 分块重叠上限：约 10%，最多 500 字符（保证跨块边界的句子有上下文） */
    private static final int MAX_OVERLAP_CHARS = 500;
    private static final int MIN_OVERLAP_CHARS = 1;

    private final ScreenModelProperties screenModelProperties;
    private final ScreenModelService screenModelService;
    private final AiInvocationService invocations;
    private final ObjectMapper objectMapper;
    private final AiModelCaller aiModelCaller;
    private final AiQuotaService quotaService;

    /**
     * @param recordingId  用于 AI 调用留痕
     * @param transcript  全文转写（DFA 命中偏移即基于此文本）
     * @param segmentsJson 转写分段 JSON（双声道时带 speaker/channel，用于给模型补充说话人信息）
     */
    public AiScreenResult screen(Long recordingId, String transcript, String segmentsJson,
                                 List<DfaHit> hits, List<CorpusExample> examples) {
        // 先取模型（不校验密钥）并组装请求体，再校验密钥：
        // 这样即便因缺密钥失败，留痕里也能看到「本该发出去什么」，排查时和看报错同样重要
        final ScreenModelService.ActiveModel model = screenModelService.active();
        String auditText = annotateBySpeaker(transcript, segmentsJson);
        int chunkChars = screenModelProperties.effectiveChunkChars();
        int maxChunks = screenModelProperties.effectiveMaxChunks();

        List<String> chunks = splitChunks(auditText, chunkChars, maxChunks + 1);
        if (chunks.size() > maxChunks) {
            log.warn("[{}] 转写文本 {} 字符，分块数超过上限 {}（每块 {} 字符），仅复核前 {} 块，其余文本截断",
                    recordingId, auditText.length(), maxChunks, chunkChars, maxChunks);
            chunks = new ArrayList<>(chunks.subList(0, maxChunks));
        }
        if (chunks.isEmpty()) {
            chunks = List.of("");
        }
        if (chunks.size() > 1) {
            log.info("[{}] 转写文本 {} 字符，分 {} 块复核（每块上限 {} 字符，重叠 {} 字符）",
                    recordingId, auditText.length(), chunks.size(), chunkChars, overlapChars(chunkChars));
        }

        List<ChunkResult> results = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            results.add(screenChunk(recordingId, model, chunks.get(i), hits, examples, i + 1, chunks.size()));
        }
        return aggregate(model, results, chunks.size());
    }

    /** 单块调用：请求/回复逐块留痕，任何异常都向上抛（由流水线降级转人工） */
    private ChunkResult screenChunk(Long recordingId, ScreenModelService.ActiveModel model, String chunk,
                                    List<DfaHit> hits, List<CorpusExample> examples, int index, int total) {
        String requestJson = buildRequestBody(model, chunk, hits, examples, index, total);
        long start = System.currentTimeMillis();
        try {
            screenModelService.requireUsable(model);
            // 配额/限流在每次真正调用前校验；超限直接抛错，不发起请求（流水线降级人工复核）
            quotaService.checkBeforeCall(model);

            JsonNode resp = aiModelCaller.call(model, requestJson, "语义复筛");
            String content = ScreenModelService.contentOf(resp);
            AiUsage usage = AiUsage.extract(resp).pricedWith(model);
            AiScreenResult result = parseResult(content, model, index);
            invocations.recordSuccess(recordingId, AiInvocationService.STAGE_SCREEN, model,
                    requestJson, content, elapsed(start), usage);
            return new ChunkResult(index, result);
        } catch (RuntimeException e) {
            invocations.recordFailure(recordingId, AiInvocationService.STAGE_SCREEN, model,
                    requestJson, e.getMessage(), elapsed(start));
            throw e;
        }
    }

    /**
     * 汇总各块结论：任一块违规即整体违规（取最高置信度的块作为结论），否则整体合规。
     * 违规但置信度低于阈值 → 标记 degraded（流水线会转人工，不会自动放行）。
     */
    private AiScreenResult aggregate(ScreenModelService.ActiveModel model, List<ChunkResult> results, int chunkCount) {
        ChunkResult highestViolation = null;
        double maxCompliantConfidence = 0;
        String firstReason = null;
        for (ChunkResult chunk : results) {
            AiScreenResult r = chunk.result();
            if (r.violation()) {
                if (highestViolation == null || r.confidence() > highestViolation.result().confidence()) {
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
            return new AiScreenResult(false, ViolationType.NONE, ViolationType.NONE.getLabel(),
                    chunkCount == 1 ? (firstReason == null ? "" : firstReason)
                            : "分块复核 " + chunkCount + " 块均判定合规",
                    chunkCount == 1 ? first.confidence() : maxCompliantConfidence,
                    "", model.providerId(), model.providerLabel(), model.model());
        }

        AiScreenResult top = highestViolation.result();
        String reason = chunkCount == 1
                ? top.reason()
                : "分块复核第 " + highestViolation.index() + "/" + chunkCount + " 块判定违规：" + top.reason();
        AiScreenResult merged = new AiScreenResult(true, top.violationType(), top.typeLabel(),
                reason, top.confidence(), top.targetSentence(),
                model.providerId(), model.providerLabel(), model.model());
        double minConfidence = screenModelProperties.effectiveMinConfidence();
        if (merged.confidence() < minConfidence) {
            String why = String.format("违规置信度 %.2f 低于阈值 %.2f，降级人工复核",
                    merged.confidence(), minConfidence);
            log.warn("[{}] AI 判定违规但置信度不足（confidence={}, minConfidence={}），转人工复检",
                    model.display(), merged.confidence(), minConfidence);
            return merged.withDegraded(why);
        }
        return merged;
    }

    private static long elapsed(long start) {
        return System.currentTimeMillis() - start;
    }

    /**
     * 组装并序列化请求体。发送的就是这个字符串，留痕里存的也是它，保证「看到的 == 发出去的」。
     */
    private String buildRequestBody(ScreenModelService.ActiveModel model, String chunk,
                                    List<DfaHit> hits, List<CorpusExample> examples, int index, int total) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (model.extraBody() != null) {
            body.putAll(model.extraBody());
        }
        body.put("model", model.model());
        body.put("temperature", screenModelProperties.getTemperature());
        if (model.jsonMode()) {
            body.put("response_format", Map.of("type", "json_object"));
        }
        if (model.maxTokens() != null && model.maxTokens() > 0) {
            body.put("max_tokens", model.maxTokens());
        }
        body.put("messages", List.of(
                Map.of("role", "system", "content", SYSTEM_PROMPT),
                Map.of("role", "user", "content", buildUserPrompt(chunk, hits, examples, index, total))));
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            log.warn("复筛请求体序列化失败（留痕将缺失请求内容）: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 严格解析模型回复：必须是 JSON 对象且含布尔 {@code violation} 字段。
     * 不满足即抛异常（流水线 catch 后降级转人工），绝不把「解析不出来」当成合规。
     */
    private AiScreenResult parseResult(String content, ScreenModelService.ActiveModel model, int chunkIndex) {
        JsonNode node;
        try {
            node = content == null ? null : objectMapper.readTree(content);
        } catch (Exception e) {
            throw invalidResponse(model, chunkIndex, "返回内容不是合法 JSON", content);
        }
        if (node == null || !node.isObject()) {
            throw invalidResponse(model, chunkIndex, "返回内容不是 JSON 对象", content);
        }
        JsonNode violationNode = node.get("violation");
        if (violationNode == null || !violationNode.isBoolean()) {
            throw invalidResponse(model, chunkIndex, "缺少布尔字段 violation", content);
        }
        boolean violation = violationNode.booleanValue();
        ViolationType type = ViolationType.fromCode(node.path("violation_type").asText());
        String reason = node.path("reason").asText("");
        double confidence = node.path("confidence").asDouble(0);
        String target = node.path("target_sentence").asText("");
        return new AiScreenResult(violation, type, type.getLabel(), reason, confidence, target,
                model.providerId(), model.providerLabel(), model.model());
    }

    private BizException invalidResponse(ScreenModelService.ActiveModel model, int chunkIndex,
                                         String why, String content) {
        String preview = ScreenModelService.truncate(content, 200);
        log.warn("[{}] AI 复筛第 {} 块返回无效（{}），按调用失败处理并降级人工复核: {}",
                model.display(), chunkIndex, why, preview);
        return new BizException("AI 复筛返回格式异常（" + why + "），无法判定，已降级人工复核 ["
                + model.display() + "]: " + preview, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    private String buildUserPrompt(String chunk, List<DfaHit> hits, List<CorpusExample> examples,
                                   int index, int total) {
        StringBuilder sb = new StringBuilder();
        sb.append("DFA 词典初筛命中的关键词（仅候选标记，不代表违规）：\n");
        if (hits == null || hits.isEmpty()) {
            sb.append("（无）\n");
        } else {
            String words = hits.stream().map(DfaHit::word).distinct().collect(Collectors.joining(", "));
            sb.append(words).append("\n");
        }
        if (examples != null && !examples.isEmpty()) {
            sb.append("\n人工复核参考案例（few-shot）：\n");
            for (CorpusExample ex : examples) {
                String tag = ex.label() == CorpusLabel.VIOLATION
                        ? "违规(" + (ex.violationType() == null ? "OTHER" : ex.violationType()) + ")"
                        : "合规";
                sb.append("【").append(tag).append("】").append(ex.transcript()).append("\n");
            }
        }
        if (total > 1) {
            sb.append("\n全文分块复核：这是第 ").append(index).append('/').append(total)
                    .append(" 块（各块独立判定，任一块违规即整体违规）。\n");
        }
        sb.append("\n待审核转写文本：\n\"\"\"\n").append(chunk).append("\n\"\"\"\n");
        sb.append("\n请根据 system 的判定标准输出 json 对象。");
        return sb.toString();
    }

    // ------------------------------------------------------------------ 分块

    /**
     * 按字符数切块（带重叠）。单块文本不超过 {@code chunkChars} 时原样返回一块。
     *
     * <p>切点优先落在换行/空格上，避免把句子从中间切开；{@code limit} 为最多收集的块数
     * （调用方传 maxChunks+1 以便判断是否发生截断）。</p>
     */
    static List<String> splitChunks(String text, int chunkChars, int limit) {
        String value = text == null ? "" : text;
        int chunkSize = Math.max(1, chunkChars);
        int maxChunks = Math.max(1, limit);
        if (value.length() <= chunkSize) {
            return List.of(value);
        }
        int overlap = overlapChars(chunkSize);
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < value.length() && chunks.size() < maxChunks) {
            int end = Math.min(value.length(), start + chunkSize);
            if (end < value.length()) {
                end = adjustBoundary(value, start, end, chunkSize);
            }
            chunks.add(value.substring(start, end));
            if (end >= value.length()) {
                break;
            }
            start = Math.max(end - overlap, start + 1);
        }
        return chunks;
    }

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
    private record ChunkResult(int index, AiScreenResult result) {
    }

    /**
     * 双声道录音按 【说话人】文本 渲染，帮助模型区分说话人（坐席违规与客户辱骂性质不同）
     */
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
}
