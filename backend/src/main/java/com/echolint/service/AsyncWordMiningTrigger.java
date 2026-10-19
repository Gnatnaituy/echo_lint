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
 * <p>单独成 Bean 的原因：{@code @Async} 依赖 Spring 代理，同类内部自调用不会异步。
 * 由 {@link ReviewService} 在事务 afterCommit 后调用，避免把可能耗时数百秒的 AI 调用
 * 留在复核事务里。异常在这里吞掉，只记日志，不影响已提交的复核结论。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AsyncWordMiningTrigger {

    private final WordMiningService wordMiningService;

    @Async("pipelineExecutor")
    public void mine(Recording recording, ViolationType type) {
        try {
            wordMiningService.mineAndSuggest(recording, type);
        } catch (Exception e) {
            log.warn("复核后词挖掘失败 recordingId={}: {}",
                    recording == null ? null : recording.getId(), e.getMessage());
        }
    }
}
