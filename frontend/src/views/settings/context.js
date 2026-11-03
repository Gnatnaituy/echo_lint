/**
 * 设置页共享上下文。
 *
 * Settings.vue 是容器：创建「当前生效模型」与「供应商注册表」两份共享状态，
 * 通过 provide 下发；各面板组件 inject 后读写同一份状态，避免重复请求，
 * 也保证跨面板联动（如切换卡片修改 view 后，当前生效卡片同步刷新）。
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
