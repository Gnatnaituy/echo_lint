/**
 * 观察元素内容宽度（ResizeObserver）
 *
 * 表格的响应式列显隐不能只看窗口宽度：侧栏折叠/展开会改变内容区宽度，
 * 而窗口宽度不变。用元素自身宽度做阈值判断，列显隐才和「表格还能不能放下」一致。
 *
 * 用法：
 *   const wrap = ref(null)
 *   const width = useElementWidth(wrap)
 *   const compact = computed(() => width.value > 0 && width.value < 880)
 *
 * 返回值在挂载前 / 环境不支持 ResizeObserver 时为 0（调用方按「非紧凑」处理即可）。
 */
import { onBeforeUnmount, onMounted, ref } from 'vue'

export function useElementWidth(elRef) {
  const width = ref(0)
  let observer = null

  function stop() {
    observer?.disconnect()
    observer = null
  }

  /** 目标是 DOM 元素或组件实例（取其 $el）都能观察 */
  function resolveEl(value) {
    if (!value) return null
    if (typeof Element !== 'undefined' && value instanceof Element) return value
    const el = value.$el
    if (el && typeof Element !== 'undefined' && el instanceof Element) return el
    return null
  }

  function start() {
    stop()
    const el = resolveEl(elRef.value)
    if (typeof ResizeObserver === 'undefined' || !el) return
    observer = new ResizeObserver(([entry]) => {
      const next = entry?.contentRect?.width || 0
      if (next > 0) width.value = next
    })
    observer.observe(el)
  }

  onMounted(start)
  onBeforeUnmount(stop)

  return width
}
