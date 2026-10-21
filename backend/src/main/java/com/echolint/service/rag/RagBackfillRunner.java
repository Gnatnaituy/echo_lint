package com.echolint.service.rag;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * RAG 启动回填（Batch 6）。
 *
 * <p>在 DataSeedRunner（预置词典/语料）之后执行：RAG 开启时把历史知识库条目与语料异步向量化。
 * 不阻塞启动；embedding 未配置时只记 WARN，条目保持 PENDING，管理员可在设置页手动重建。</p>
 */
@Slf4j
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
@RequiredArgsConstructor
public class RagBackfillRunner implements ApplicationRunner {

    private final RagIndexService indexService;
    private final RagSettingsService settings;
    private final RagEmbeddingClient embeddingClient;

    @Override
    public void run(ApplicationArguments args) {
        if (!settings.isEnabled()) {
            log.info("RAG 未开启（可在系统设置页启用）；跳过启动回填");
            return;
        }
        if (!embeddingClient.available()) {
            log.warn("RAG 已开启但 embedding 供应商不可用（未配置密钥/供应商停用），"
                    + "知识库索引将保持 PENDING；请在「AI 供应商」补全密钥后重建索引");
            return;
        }
        try {
            indexService.backfillOnStartup();
        } catch (Exception e) {
            log.warn("RAG 启动回填失败（不影响启动）：{}", e.getMessage());
        }
    }
}
