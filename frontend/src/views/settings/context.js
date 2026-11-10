/**
 * 设置页共享上下文。
 *
 * SettingsLayout.vue 是容器：创建「AI 复筛模型」与「供应商注册表」两份共享状态，
 * 通过 provide 下发；各子页面组件 inject 后读写同一份状态，避免重复请求，
 * 也保证联动（如供应商编辑后状态行 / 网格 / 模型候选一起刷新）。
 */
import { inject } from 'vue'

export const SETTINGS_CONTEXT = Symbol('echolint.settings.context')

export function useSettingsContext() {
  const context = inject(SETTINGS_CONTEXT)
  if (!context) {
    throw new Error('Settings context is not available')
  }
  return context
}
