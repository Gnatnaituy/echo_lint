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
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * AI 语义复筛：在 DFA 命中的基础上，结合 few-shot 语料判断是否构成真实违规。
 *
 * 具体用哪个模型由 {@link ScreenModelService} 在运行时决定（OpenAI / DeepSeek / 自建网关均可），
 * 本类只负责组装提示词与解析结论。
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
            """;

    private final ScreenModelProperties screenModelProperties;
    private final ScreenModelService screenModelService;
    private final ObjectMapper objectMapper;

    /**
     * @param transcript  全文转写（DFA 命中偏移即基于此文本）
     * @param segmentsJson 转写分段 JSON（双声道时带 speaker/channel，用于给模型补充说话人信息）
     */
    public AiScreenResult screen(String transcript, String segmentsJson, List<DfaHit> hits, List<CorpusExample> examples) {
        ScreenModelService.ActiveModel model = screenModelService.requireActive();

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
                Map.of("role", "user", "content", buildUserPrompt(transcript, segmentsJson, hits, examples))));

        JsonNode resp;
        try {
            resp = model.client().post()
                    .uri(model.chatPath())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, clientResponse ->
                            clientResponse.bodyToMono(String.class).map(errBody ->
                                    new BizException("语义复筛调用失败 [" + model.display() + "] (HTTP "
                                            + clientResponse.statusCode().value() + "): "
                                            + ScreenModelService.extractErrorMessage(objectMapper, errBody),
                                            HttpStatus.INTERNAL_SERVER_ERROR)))
                    .bodyToMono(JsonNode.class)
                    .block(Duration.ofSeconds(model.timeoutSeconds()));
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("语义复筛调用异常 [" + model.display() + "]: " + e.getMessage(),
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }

        if (resp == null) {
            throw new BizException("语义复筛超时或返回为空 [" + model.display() + "]", HttpStatus.INTERNAL_SERVER_ERROR);
        }
        return parseResult(ScreenModelService.contentOf(resp), model);
    }

    private AiScreenResult parseResult(String content, ScreenModelService.ActiveModel model) {
        try {
            JsonNode node = objectMapper.readTree(content);
            boolean violation = node.path("violation").asBoolean(false);
            ViolationType type = ViolationType.fromCode(node.path("violation_type").asText());
            String reason = node.path("reason").asText("");
            double confidence = node.path("confidence").asDouble(0);
            String target = node.path("target_sentence").asText("");
            return new AiScreenResult(violation, type, type.getLabel(), reason, confidence, target,
                    model.providerId(), model.providerLabel(), model.model());
        } catch (Exception e) {
            log.warn("[{}] AI 复筛返回无法解析: {}", model.display(), content);
            throw new BizException("AI 复筛返回格式异常，无法解析 [" + model.display() + "]: "
                    + ScreenModelService.truncate(content, 200), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private String buildUserPrompt(String transcript, String segmentsJson, List<DfaHit> hits, List<CorpusExample> examples) {
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
        String auditText = annotateBySpeaker(transcript, segmentsJson);
        sb.append("\n待审核转写文本：\n\"\"\"\n").append(ScreenModelService.truncate(auditText, 12000)).append("\n\"\"\"\n");
        sb.append("\n请根据 system 的判定标准输出 json 对象。");
        return sb.toString();
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
