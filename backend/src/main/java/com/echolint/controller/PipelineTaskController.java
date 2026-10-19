package com.echolint.controller;

import com.echolint.domain.AuditAction;
import com.echolint.domain.TaskStatus;
import com.echolint.dto.PipelineTaskView;
import com.echolint.service.AuditService;
import com.echolint.service.PipelineTaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 异步任务队列（ADMIN，Batch 3b-1）：分页查看流水线任务、手动重试死信/失败任务。
 *
 * <p>URL 级权限在 SecurityConfig 中限制为 ADMIN；这里不再重复判断角色。</p>
 */
@RestController
@RequestMapping("/api/tasks")
@RequiredArgsConstructor
public class PipelineTaskController {

    /** 列表分页大小硬上限，与录音列表保持一致 */
    private static final int MAX_PAGE_SIZE = 100;

    private final PipelineTaskService pipelineTaskService;
    private final AuditService auditService;

    /** 任务分页：可按状态、录音过滤；size 上限 100 */
    @GetMapping
    public Page<PipelineTaskView> list(@RequestParam(required = false) String status,
                                       @RequestParam(required = false) Long recordingId,
                                       @RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "20") int size) {
        TaskStatus parsed = PipelineTaskService.parseStatus(status);
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        PageRequest pageable = PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "id"));
        return pipelineTaskService.query(parsed, recordingId, pageable);
    }

    /** 手动重试：仅 DEAD/FAILED 任务（服务层校验，否则 409）；写审计 TASK_RETRY */
    @PostMapping("/{id}/retry")
    public PipelineTaskView retry(@PathVariable Long id) {
        PipelineTaskView view = pipelineTaskService.retry(id);
        auditService.record(AuditAction.TASK_RETRY, "PIPELINE_TASK", id,
                "重试任务（录音 #" + view.recordingId() + "，状态 " + view.status() + "）");
        return view;
    }
}
