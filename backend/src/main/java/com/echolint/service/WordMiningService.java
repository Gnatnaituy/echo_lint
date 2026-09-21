package com.echolint.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.echolint.config.OpenAiProperties;
import com.echolint.dfa.WordNormalizer;
import com.echolint.domain.ViolationType;
import com.echolint.domain.WordSeverity;
import com.echolint.domain.WordSource;
import com.echolint.entity.DictionaryWord;
import com.echolint.entity.PipelineLog;
import com.echolint.entity.Recording;
import com.echolint.repository.DictionaryWordRepository;
import com.echolint.repository.PipelineLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 词典挖掘：人工确认违规的录音，由 AI 提炼违规关键词/短语，加入词典（默认停用，待管理员启用）。
 * 形成"人工复检 -> 语料库 -> 词典自增长"的闭环。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WordMiningService {

    private static final String SYSTEM_PROMPT = """
            你是合规词典维护助手。系统判定一段客服录音转写文本为真实违规，类型已给出。
            请从中提炼适合关键词初筛的违规用词或短语：要求简短（1~5 个词）、小写、
            删除标点，能被关键词过滤器直接命中；不要提取过于常见的中性词。
            只输出一个 JSON 对象：{"keywords": ["word1", "phrase two", ...]}，最多 10 个。
            """;

    private final OpenAiProperties props;
    private final WebClient openAiWebClient;
    private final ObjectMapper objectMapper;
    private final DictionaryWordRepository dictionaryWordRepository;
    private final PipelineLogRepository pipelineLogRepository;

    /**
     * 挖掘并入库新词。落库结果通过返回统计；处理异常不抛出（只记日志），避免阻塞复检。
     *
     * @return 新增词数量
     */
    public int mineAndSuggest(Recording recording, ViolationType type) {
        try {
            List<String> keywords = extractKeywords(recording.getTranscript(), type);
            int added = 0;
            List<String> addedWords = new ArrayList<>();
            for (String kw : keywords) {
                String normalized = WordNormalizer.normalize(kw);
                if (normalized.isEmpty()) {
                    continue;
                }
                Optional<DictionaryWord> existing = dictionaryWordRepository.findByWordIgnoreCase(normalized);
                if (existing.isPresent()) {
                    continue;
                }
                DictionaryWord w = new DictionaryWord();
                w.setWord(normalized);
                w.setCategory(type.name());
                w.setSeverity(WordSeverity.MEDIUM);
                w.setSource(WordSource.MINED);
                w.setEnabled(false);
                dictionaryWordRepository.save(w);
                added++;
                addedWords.add(normalized);
            }
            if (added > 0) {
                saveLog(recording.getId(), "MINING", "INFO",
                        "从确认违规录音挖掘新词 " + added + " 个（停用待审核）: " + String.join(", ", addedWords));
                log.info("录音 {} 挖掘新词 {} 个", recording.getId(), added);
            }
            return added;
        } catch (Exception e) {
            log.warn("词挖掘失败 recordingId={}: {}", recording.getId(), e.getMessage());
            saveLog(recording.getId(), "MINING", "WARN", "词挖掘失败: " + e.getMessage());
            return 0;
        }
    }

    private List<String> extractKeywords(String transcript, ViolationType type) throws Exception {
        if (!props.apiKeyConfigured()) {
            return List.of();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", props.getScreenModel());
        body.put("temperature", 0.2);
        body.put("response_format", Map.of("type", "json_object"));
        body.put("messages", List.of(
                Map.of("role", "system", "content", SYSTEM_PROMPT),
                Map.of("role", "user", "content", "违规类型: " + type.name() + "\n转写文本:\n\"\"\"\n"
                        + SemanticScreeningService.truncate(transcript == null ? "" : transcript, 8000) + "\n\"\"\"")));

        JsonNode resp = openAiWebClient.post()
                .uri(props.getChatPath())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .onStatus(HttpStatusCode::isError, clientResponse ->
                        clientResponse.bodyToMono(String.class).map(errBody ->
                                new RuntimeException("HTTP " + clientResponse.statusCode().value() + ": " + errBody)))
                .bodyToMono(JsonNode.class)
                .block(Duration.ofSeconds(props.getRequestTimeoutSeconds()));

        if (resp == null) {
            return List.of();
        }
        String content = resp.path("choices").path(0).path("message").path("content").asText("");
        JsonNode node = objectMapper.readTree(content);
        List<String> keywords = new ArrayList<>();
        node.path("keywords").forEach(k -> keywords.add(k.asText()));
        return keywords;
    }

    private void saveLog(Long recordingId, String stage, String level, String message) {
        PipelineLog logEntry = new PipelineLog();
        logEntry.setRecordingId(recordingId);
        logEntry.setStage(stage);
        logEntry.setLevel(level);
        logEntry.setMessage(message);
        pipelineLogRepository.save(logEntry);
    }
}