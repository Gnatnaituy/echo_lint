package com.echolint.config;

import com.echolint.asr.AsrSettingsService;
import com.echolint.service.IntegrationStatusService;
import com.echolint.service.ScreenModelService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.util.StringUtils;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class AppConfig {

    /**
     * 流水线执行线程池。
     *
     * <p>拒绝策略用 {@link java.util.concurrent.ThreadPoolExecutor.AbortPolicy}：队列满时抛 {@link
     * java.util.concurrent.RejectedExecutionException}，由 {@code PipelineTaskScheduler} 捕获后把已领取的任务退回
     * PENDING，等下一个调度周期重试。此前用 CallerRunsPolicy 会让 调度线程亲自跑整条流水线（甚至数十秒~数分钟），阻塞后续任务派发。
     */
    @Bean(name = "pipelineExecutor")
    public ThreadPoolTaskExecutor pipelineExecutor(AppProperties appProperties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(appProperties.getPipelinePoolSize());
        executor.setMaxPoolSize(Math.max(appProperties.getPipelinePoolSize() * 2, 4));
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("pipeline-");
        executor.setRejectedExecutionHandler(
                new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }

    /**
     * 词挖掘专用线程池（P2-6）。
     *
     * <p>复核后词挖掘会串行调用多次 AI（注释自述可能数百秒），此前复用 {@code pipelineExecutor} 会占满流水线线程。这里独立成 1 线程小池：队列满时直接丢弃并
     * WARN，绝不阻塞复核提交 （词挖掘是尽力而为的旁路能力，遗漏的候选词可下次复核再挖掘）。
     */
    @Bean(name = "miningExecutor")
    public ThreadPoolTaskExecutor miningExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("mining-");
        executor.setRejectedExecutionHandler(
                (task, pool) -> log.warn("词挖掘队列已满（{}），丢弃本次异步挖掘任务", pool.getQueue().size()));
        executor.initialize();
        return executor;
    }

    /**
     * Webhook 通知专用线程池（Batch 2b-2）。
     *
     * <p>通知是尽力而为的旁路能力：独立小池子避免慢接收端占用流水线线程； 队列满时直接丢弃并 warn，绝不阻塞复核/流水线主流程。
     */
    @Bean(name = "notificationExecutor")
    public ThreadPoolTaskExecutor notificationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("notify-");
        executor.setRejectedExecutionHandler((task, pool) -> log.warn("通知队列已满，丢弃本次 Webhook 通知"));
        executor.initialize();
        return executor;
    }

    /**
     * RAG 索引/文档解析线程池（Batch 6）。
     *
     * <p>知识库写入、语料回馈、文档导入都异步向量化；与流水线线程池隔离， 避免 embedding 网络耗时拖慢录音稽核主流程。队列满时用 AbortPolicy 真正抛 {@link
     * java.util.concurrent.RejectedExecutionException}（P3-3）：之前自定义 handler
     * 只打日志，调用方无法感知丢弃，重建索引还会虚报「已入队 N 条」。现在由 {@code RagIndexService} 统计 skipped，文档导入侧把条目置 FAILED
     * 而不停留在 PENDING。
     */
    @Bean(name = "ragIndexExecutor")
    public ThreadPoolTaskExecutor ragIndexExecutor(RagProperties ragProperties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        int poolSize = ragProperties.effectiveIndexPoolSize();
        executor.setCorePoolSize(poolSize);
        executor.setMaxPoolSize(poolSize);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("rag-index-");
        executor.setRejectedExecutionHandler(
                new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }

    @Bean
    public ApplicationRunner startupAdvice(
            ScreenModelService screenModelService,
            AsrSettingsService asrSettingsService,
            AppProperties appProperties,
            ScreenModelProperties screenModelProperties,
            NotifyProperties notifyProperties,
            IntegrationStatusService integrationStatusService) {
        return args -> {
            // 语音转写：运行时 provider（本地 whisper.cpp / 阿里云百炼），每次转写按当前配置解析
            try {
                AsrSettingsService.Resolved asr = asrSettingsService.current();
                if (asr.ready()) {
                    log.info(
                            "语音转写：{}（provider={}，超时 {}s，上传上限 {}MB；可在「系统设置 → 语音转写」运行时切换）",
                            asr.type().label(),
                            asr.type().value(),
                            asr.config().timeoutSeconds(),
                            appProperties.getMaxSizeBytes() / 1024 / 1024);
                } else {
                    log.warn("{} —— 录音上传后将置为 FAILED", asr.unreadyMessage());
                }
            } catch (Exception e) {
                log.warn("语音转写配置解析失败：{}", e.getMessage());
            }
            try {
                var active = screenModelService.active();
                if (active.apiKeyConfigured()) {
                    log.info(
                            "AI 语义复筛模型：{} · {}（可在系统设置页运行时切换）",
                            active.providerLabel(),
                            active.model());
                } else {
                    log.warn(
                            "AI 语义复筛模型 {} · {} 未配置 API Key —— DFA 命中的录音将降级转人工复检，"
                                    + "请在「系统设置」中切换模型或补全密钥。",
                            active.providerLabel(),
                            active.model());
                }
            } catch (Exception e) {
                log.warn("AI 语义复筛模型解析失败：{}", e.getMessage());
            }
            if (appProperties.samplingEnabled()) {
                log.info(
                        "合规抽样已开启：DFA 未命中录音按 {} 比例进入 AI 抽样复核（app.sample.dfa-miss-rate）",
                        String.format("%.2f", appProperties.dfaMissSampleRate()));
            } else {
                log.info("合规抽样未开启（app.sample.dfa-miss-rate=0，DFA 未命中直接归档）");
            }
            if (appProperties.retentionEnabled()) {
                log.info(
                        "数据保留清理已开启：AI 留痕 {} 天 / 审计日志 {} 天 / 流水线日志 {} 天（cron={}；原始录音不自动删除）",
                        appProperties.retentionAiInvocationsDays(),
                        appProperties.retentionAuditLogsDays(),
                        appProperties.retentionPipelineLogsDays(),
                        appProperties.getRetention().getCron());
            } else {
                log.info("数据保留清理未开启（app.retention.enabled=false）：历史留痕/日志不会自动清理");
            }
            if (notifyProperties.usable()) {
                log.info(
                        "Webhook 通知已开启：积压阈值 {} 条，超时 {}s，{}",
                        notifyProperties.effectiveBacklogThreshold(),
                        notifyProperties.effectiveTimeoutSeconds(),
                        StringUtils.hasText(notifyProperties.getWebhookSecret())
                                ? "已配置 HMAC-SHA256 签名"
                                : "未配置签名密钥");
            } else if (notifyProperties.isEnabled()) {
                log.warn("Webhook 通知已开启但未配置可用的 http(s) webhook-url —— 通知将被跳过");
            } else {
                log.info("Webhook 通知未开启（app.notify.enabled=false）");
            }
            if (screenModelProperties.getDailyTokenLimit() > 0
                    || screenModelProperties.getRequestsPerMinute() > 0) {
                log.info(
                        "AI 配额已开启：每日 token 上限 {}（0=不限），每供应商每分钟 {} 次（0=不限）；" + "超限将跳过调用并降级人工复核",
                        screenModelProperties.getDailyTokenLimit(),
                        screenModelProperties.getRequestsPerMinute());
            } else {
                log.info("AI 配额未开启（daily-token-limit=0、requests-per-minute=0）");
            }
            ScreenModelProperties.Ab ab = screenModelProperties.getAb();
            if (ab != null && ab.isEnabled()) {
                log.info(
                        "AI 语义复筛 A/B 分流已开启：{}% 概率走 B 组供应商 {} · {}（不可用时回退当前生效模型）",
                        ab.effectivePercent(),
                        StringUtils.hasText(ab.getProviderId()) ? ab.getProviderId() : "（未配置）",
                        StringUtils.hasText(ab.getModel()) ? ab.getModel() : "（供应商默认模型）");
            } else {
                log.info("AI 语义复筛 A/B 分流未开启（app.screen-models.ab.enabled=false）");
            }
            // 集成适配器（Batch 5）：只打印启用/配置状态与供应商名，绝不打印任何密钥
            IntegrationStatusService.IntegrationsView integrations =
                    integrationStatusService.status();
            log.info(
                    "集成适配器：租户={}({}) SSO={}({}) 流式ASR={}({}) 说话人分离={}({})",
                    integrations.tenant().enabled() ? "启用" : "关闭",
                    integrations.tenant().mode(),
                    integrations.sso().enabled() ? "启用" : "关闭",
                    integrations.sso().provider(),
                    integrations.streaming().enabled() ? "启用" : "关闭",
                    integrations.streaming().provider(),
                    integrations.diarization().enabled() ? "启用" : "关闭",
                    integrations.diarization().provider());
        };
    }
}
