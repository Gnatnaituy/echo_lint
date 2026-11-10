/**
 * AI 供应商注册表共享状态（仅 ADMIN）。
 *
 * 复筛页的供应商网格（选择 / 编辑 / 删除）与 A/B 评测（候选供应商）共用这一份
 * providers / 加载状态：页面内只请求一次，CRUD 后统一刷新；非 ADMIN 不加载。
 */
import { ref } from 'vue'
import api from '../../api'
import { useI18n } from '../../i18n'
import { adminErrorMessage } from './adminErrors'

export function useProviderRegistry(canWrite) {
  const { t } = useI18n()

  const providers = ref([])
  const providersLoading = ref(false)
  const providersError = ref('')

  async function load() {
    if (!canWrite.value) return
    providersLoading.value = true
    providersError.value = ''
    try {
      providers.value = await api.listProviders()
    } catch (e) {
      providers.value = []
      providersError.value = adminErrorMessage(e, t('settings.provider.loadFailed'))
    } finally {
      providersLoading.value = false
    }
  }

  return {
    providers,
    providersLoading,
    providersError,
    load
  }
}
