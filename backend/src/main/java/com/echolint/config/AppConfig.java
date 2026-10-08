package com.echolint.config;

import com.echolint.dfa.WordNormalizer;
import com.echolint.domain.WordSeverity;
import com.echolint.domain.ViolationType;
import com.echolint.entity.DictionaryWord;
import com.echolint.repository.DictionaryWordRepository;
import com.echolint.service.ScreenModelService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.List;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class AppConfig {

    private final WhisperProperties whisperProperties;

    @Bean
    public WebClient whisperWebClient() {
        HttpClient httpClient = HttpClient.create()
                .responseTimeout(Duration.ofSeconds(whisperProperties.getRequestTimeoutSeconds()));
        return WebClient.builder()
                .baseUrl(whisperProperties.getBaseUrl())
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + whisperProperties.getApiKey())
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Bean(name = "pipelineExecutor")
    public ThreadPoolTaskExecutor pipelineExecutor(AppProperties appProperties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(appProperties.getPipelinePoolSize());
        executor.setMaxPoolSize(Math.max(appProperties.getPipelinePoolSize() * 2, 4));
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("pipeline-");
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }

    @Bean
    public ApplicationRunner startupAdvice(ScreenModelService screenModelService, AppProperties appProperties) {
        return args -> {
            if (whisperProperties.apiKeyConfigured()) {
                log.info("语音转写：{}（模型 {}，上传上限 {}MB）",
                        whisperProperties.getBaseUrl(), whisperProperties.getModel(),
                        appProperties.getMaxSizeBytes() / 1024 / 1024);
                // 官方 API 单文件上限 25MB，上传上限放宽后大文件会在远端才失败，提前提醒
                if (whisperProperties.getBaseUrl().contains("api.openai.com")
                        && appProperties.getMaxSizeBytes() > 26214400L) {
                    log.warn("上传上限已放宽到 {}MB，但转写走的是 OpenAI 官方 API（单文件上限 25MB）。" +
                            "超过 25MB 的录音会在调用远端时失败；如需处理长录音，请把 WHISPER_BASE_URL " +
                            "指向自建服务（如本机 whisper.cpp，见 tools/whisper-server.sh）。",
                            appProperties.getMaxSizeBytes() / 1024 / 1024);
                }
            } else {
                log.warn("未配置转写服务密钥 —— 录音上传后将置为 FAILED。" +
                        "请设置 WHISPER_API_KEY（自建 whisper.cpp 可填任意非空值）或 OPENAI_API_KEY。");
            }
            try {
                var active = screenModelService.active();
                if (active.apiKeyConfigured()) {
                    log.info("AI 语义复筛模型：{} · {}（可在系统设置页运行时切换）", active.providerLabel(), active.model());
                } else {
                    log.warn("AI 语义复筛模型 {} · {} 未配置 API Key —— DFA 命中的录音将降级转人工复检，" +
                            "请在「系统设置」中切换模型或补全密钥。", active.providerLabel(), active.model());
                }
            } catch (Exception e) {
                log.warn("AI 语义复筛模型解析失败：{}", e.getMessage());
            }
        };
    }
}