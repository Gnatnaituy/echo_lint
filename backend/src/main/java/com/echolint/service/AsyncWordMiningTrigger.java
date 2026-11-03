package com.echolint.service;

import com.echolint.domain.ViolationType;
import com.echolint.entity.Recording;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * 复核结论落库后的异步词挖掘入口。
 *
 * <p>单独成 Bean 的原因：{@code @Async} 依赖 Spring 代理，同类内部自调用不会异步。 由 {@link ReviewService} 在事务 afterCommit
 * 后调用，避免把可能耗时数百秒的 AI 调用 留在复核事务里。异常在这里吞掉，只记日志，不影响已提交的复核结论。
 *
 * <p>使用独立的 {@code miningExecutor} 小池（P2-6）：词挖掘会串行调用多次 AI，若复用 {@code pipelineExecutor}
 * 会占满流水线线程、把调度线程拖进长时间同步执行；小池队列满时 直接丢弃（丢弃策略在 {@code AppConfig#miningExecutor}），绝不反过来阻塞复核提交。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AsyncWordMiningTrigger {

    private final WordMiningService wordMiningService;

    @Async("miningExecutor")
    public void mine(Recording recording, ViolationType type) {
        try {
            wordMiningService.mineAndSuggest(recording, type);
        } catch (Exception e) {
            log.warn(
                    "复核后词挖掘失败 recordingId={}: {}",
                    recording == null ? null : recording.getId(),
                    e.getMessage());
        }
    }
}
