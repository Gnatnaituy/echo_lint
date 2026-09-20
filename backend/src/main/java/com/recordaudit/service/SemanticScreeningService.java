package com.recordaudit.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recordaudit.config.OpenAiProperties;
import com.recordaudit.dfa.DfaHit;
import com.recordaudit.domain.CorpusLabel;
import com.recordaudit.domain.ViolationType;
import com.recordaudit.exception.BizException;
import com.recordaudit.service.AiScreenResult.CorpusExample;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * AI 语义复筛（GPT-4o mini）：在 DFA 命中的基础上，结合 few-shot 语料判断是否构成真实违规
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

            只输出一个 JSON 对象，不要输出任何其他内容，格式：
            {"violation": true或false, "violation_type": "INSULT"或"DISCRIMINATION"或"THREAT"或"HARASSMENT"或"SEXUAL"或"FRAUD"或"PRIVACY"或"OTHER"或"NONE", "reason": "简短英文理由", "confidence": 0.0到1.0的小数, "target_sentence": "违规原句，无违规则为空字符串"}
            """;

    private final OpenAiProperties props;
    private final WebClient openAiWebClient;
    private final ObjectMapper objectMapper;

    public AiScreenResult screen(String transcript, List<DfaHit> hits, List<CorpusExample> examples) {
        if (!props.apiKeyConfigured()) {
            throw new BizException("未配置 OPENAI_API_KEY，无法进行语义复筛");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", props.getScreenModel());
        body.put("temperature", props.getScreenTemperature());
        body.put("response_format", Map.of("type", "json_object"));
        body.put("messages", List.of(
                Map.of("role", "system", "content", SYSTEM_PROMPT),
                Map.of("role", "user", "content", buildUserPrompt(transcript, hits, examples))));

        JsonNode resp;
        try {
            resp = openAiWebClient.post()
                    .uri(props.getChatPath())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, clientResponse ->
                            clientResponse.bodyToMono(String.class).map(errBody ->
                                    new BizException("语义复筛调用失败 (HTTP " + clientResponse.statusCode().value() + "): "
                                            + extractErrorMessage(errBody), HttpStatus.INTERNAL_SERVER_ERROR)))
                    .bodyToMono(JsonNode.class)
                    .block(Duration.ofSeconds(props.getRequestTimeoutSeconds()));
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("语义复筛调用异常: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }

        if (resp == null) {
            throw new BizException("语义复筛超时或返回为空", HttpStatus.INTERNAL_SERVER_ERROR);
        }
        String content = resp.path("choices").path(0).path("message").path("content").asText("");
        return parseResult(content);
    }

    private AiScreenResult parseResult(String content) {
        try {
            JsonNode node = objectMapper.readTree(content);
            boolean violation = node.path("violation").asBoolean(false);
            ViolationType type = ViolationType.fromCode(node.path("violation_type").asText());
            String reason = node.path("reason").asText("");
            double confidence = node.path("confidence").asDouble(0);
            String target = node.path("target_sentence").asText("");
            return new AiScreenResult(violation, type, type.getLabel(), reason, confidence, target);
        } catch (Exception e) {
            log.warn("AI 复筛返回无法解析: {}", content);
            throw new BizException("AI 复筛返回格式异常，无法解析: " + (content != null && content.length() > 200 ? content.substring(0, 200) : content),
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private String buildUserPrompt(String transcript, List<DfaHit> hits, List<CorpusExample> examples) {
        StringBuilder sb = new StringBuilder();
        sb.append("DFA 词典初筛命中的关键词（仅候选标记，不代表违规）：\n");
        if (hits.isEmpty()) {
            sb.append("（无）\n");
        } else {
            for (DfaHit h : hits) {
                sb.append("- ").append(h.word()).append(" [").append(h.start()).append(", ").append(h.end()).append(")\n");
            }
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
        sb.append("\n待审核转写文本：\n\"\"\"\n").append(truncate(transcript, 12000)).append("\n\"\"\"\n");
        sb.append("\n请根据 system 的判定标准输出 JSON 对象。");
        return sb.toString();
    }

    private String extractErrorMessage(String errorBody) {
        try {
            JsonNode node = objectMapper.readTree(errorBody);
            String msg = node.path("error").path("message").asText("");
            if (!msg.isBlank()) {
                return msg;
            }
        } catch (Exception ignored) {
            // fall through
        }
        return errorBody != null && errorBody.length() > 300 ? errorBody.substring(0, 300) : String.valueOf(errorBody);
    }

    static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}