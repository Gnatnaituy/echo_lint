/**
 * 响应式 / 无障碍可测纯逻辑（不依赖 DOM，便于 node --test）。
 */

/** 窄屏断点：<= 900px 侧栏切换为抽屉 */
export const NARROW_BREAKPOINT = 900

export function isNarrowWidth(width, breakpoint = NARROW_BREAKPOINT) {
  const value = Number(width)
  if (!Number.isFinite(value)) return false
  return value <= breakpoint
}

/** 键盘事件是否为 Escape（兼容 keyCode，部分输入法/老浏览器只给 keyCode） */
export function isEscapeKey(event) {
  if (!event) return false
  return event.key === 'Escape' || event.code === 'Escape' || event.keyCode === 27
}

/** 抽屉开合状态：窄屏才允许打开；切回宽屏强制关闭 */
export function resolveDrawerOpen({ narrow, requested }) {
  return !!narrow && !!requested
}

/** ESC 是否应关闭抽屉（需要处于窄屏且抽屉已打开） */
export function shouldCloseDrawerOnKey({ narrow, open, key }) {
  return !!narrow && !!open && (key === 'Escape' || key === 27)
}

/** 焦点在可聚焦元素序列中的下一个索引（用于抽屉内 Tab 循环） */
export function nextFocusIndex(current, length, step = 1) {
  const total = Number(length)
  if (!Number.isFinite(total) || total <= 0) return -1
  const index = Number.isFinite(Number(current)) ? Math.trunc(Number(current)) : 0
  return ((index + step) % total + total) % total
}
