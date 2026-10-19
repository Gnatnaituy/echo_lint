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
import org.springframework.util.StringUtils;
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

    /**
     * Webhook 通知专用线程池（Batch 2b-2）。
     *
     * <p>通知是尽力而为的旁路能力：独立小池子避免慢接收端占用流水线线程；
     * 队列满时直接丢弃并 warn，绝不阻塞复核/流水线主流程。</p>
     */
    @Bean(name = "notificationExecutor")
    public ThreadPoolTaskExecutor notificationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("notify-");
        executor.setRejectedExecutionHandler((task, pool) ->
                log.warn("通知队列已满，丢弃本次 Webhook 通知"));
        executor.initialize();
        return executor;
    }

    @Bean
    public ApplicationRunner startupAdvice(ScreenModelService screenModelService, AppProperties appProperties,
                                           ScreenModelProperties screenModelProperties,
                                           NotifyProperties notifyProperties) {
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
            if (appProperties.samplingEnabled()) {
                log.info("合规抽样已开启：DFA 未命中录音按 {} 比例进入 AI 抽样复核（app.sample.dfa-miss-rate）",
                        String.format("%.2f", appProperties.dfaMissSampleRate()));
            } else {
                log.info("合规抽样未开启（app.sample.dfa-miss-rate=0，DFA 未命中直接归档）");
            }
            if (notifyProperties.usable()) {
                log.info("Webhook 通知已开启：积压阈值 {} 条，超时 {}s，{}", notifyProperties.effectiveBacklogThreshold(),
                        notifyProperties.effectiveTimeoutSeconds(),
                        StringUtils.hasText(notifyProperties.getWebhookSecret())
                                ? "已配置 HMAC-SHA256 签名" : "未配置签名密钥");
            } else if (notifyProperties.isEnabled()) {
                log.warn("Webhook 通知已开启但未配置可用的 http(s) webhook-url —— 通知将被跳过");
            } else {
                log.info("Webhook 通知未开启（app.notify.enabled=false）");
            }
            if (screenModelProperties.getDailyTokenLimit() > 0 || screenModelProperties.getRequestsPerMinute() > 0) {
                log.info("AI 配额已开启：每日 token 上限 {}（0=不限），每供应商每分钟 {} 次（0=不限）；"
                                + "超限将跳过调用并降级人工复核",
                        screenModelProperties.getDailyTokenLimit(),
                        screenModelProperties.getRequestsPerMinute());
            } else {
                log.info("AI 配额未开启（daily-token-limit=0、requests-per-minute=0）");
            }
            ScreenModelProperties.Ab ab = screenModelProperties.getAb();
            if (ab != null && ab.isEnabled()) {
                log.info("AI 语义复筛 A/B 分流已开启：{}% 概率走 B 组供应商 {} · {}（不可用时回退当前生效模型）",
                        ab.effectivePercent(),
                        StringUtils.hasText(ab.getProviderId()) ? ab.getProviderId() : "（未配置）",
                        StringUtils.hasText(ab.getModel()) ? ab.getModel() : "（供应商默认模型）");
            } else {
                log.info("AI 语义复筛 A/B 分流未开启（app.screen-models.ab.enabled=false）");
            }
        };
    }
}