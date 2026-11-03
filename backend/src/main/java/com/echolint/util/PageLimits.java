package com.echolint.util;

/**
 * 分页参数钳制工具：统一列表接口的 page size 下限（1）与上限（由调用方传入）。
 *
 * <p>此前各 Controller 手写 {@code Math.min(Math.max(size, 1), MAX_PAGE_SIZE)}， 新接口容易漏掉上限导致一次拉全表（见审计
 * BC-09）。
 */
public final class PageLimits {

    private PageLimits() {}

    /**
     * 把请求的 page size 钳制到 {@code [1, max]}。
     *
     * @param size 客户端传入的 size，可能为 0 或负数
     * @param max 该接口允许的最大 page size
     */
    public static int clamp(int size, int max) {
        return Math.min(Math.max(size, 1), max);
    }
}
