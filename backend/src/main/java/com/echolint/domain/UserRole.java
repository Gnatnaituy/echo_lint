package com.echolint.domain;

/**
 * 系统角色：管理员 / 复核员 / 只读。
 *
 * <p>权限矩阵在后端 {@code SecurityConfig} 中按 URL 独立校验，前端隐藏入口仅用于体验。</p>
 */
public enum UserRole {
    /** 管理员：用户管理、审计日志、敏感词/设置写操作、语料删除 */
    ADMIN,
    /** 复核员：人工复检、录音上传/重试/删除/认领/重开、语料新增 */
    REVIEWER,
    /** 只读：仅可查看 */
    VIEWER
}
