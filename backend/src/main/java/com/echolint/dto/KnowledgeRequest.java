package com.echolint.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 知识库条目新增 / 修改请求（Batch 4a）。
 */
public record KnowledgeRequest(
        @NotBlank(message = "标题不能为空")
        @Size(max = 200, message = "标题最长 200 个字符")
        String title,
        @Size(max = 64, message = "分类最长 64 个字符")
        String category,
        @Size(max = 64, message = "违规类型最长 64 个字符")
        String violationType,
        @Size(max = 512, message = "标签最长 512 个字符")
        String tags,
        @Size(max = 100000, message = "正文过长")
        String content,
        Boolean enabled) {
}
