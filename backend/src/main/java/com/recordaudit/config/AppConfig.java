package com.recordaudit.config;

import com.recordaudit.dfa.WordNormalizer;
import com.recordaudit.domain.WordSeverity;
import com.recordaudit.domain.ViolationType;
import com.recordaudit.entity.DictionaryWord;
import com.recordaudit.repository.DictionaryWordRepository;
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

    private final OpenAiProperties openAiProperties;

    @Bean
    public WebClient openAiWebClient() {
        HttpClient httpClient = HttpClient.create()
                .responseTimeout(Duration.ofSeconds(openAiProperties.getRequestTimeoutSeconds()));
        return WebClient.builder()
                .baseUrl(openAiProperties.getBaseUrl())
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + openAiProperties.getApiKey())
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
    public ApplicationRunner openAiKeyWarning() {
        return args -> {
            if (!openAiProperties.apiKeyConfigured()) {
                log.warn("未配置 OPENAI_API_KEY —— Whisper 转写与 AI 语义复筛将不可用（上传会置为 FAILED）。" +
                        "请通过环境变量 OPENAI_API_KEY 提供，或创建 .env 文件后执行 docker compose up。");
            }
        };
    }
}