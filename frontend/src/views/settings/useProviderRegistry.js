/**
 * AI 供应商注册表共享状态（仅 ADMIN）。
 *
 * ③ 供应商注册表、② 切换卡片（停用供应商不可选）、④ A/B 评测（候选供应商）
 * 共用这一份 providers / 加载状态：页面内只请求一次，CRUD 后统一刷新。
 */
import { computed, ref } from 'vue'
import api from '../../api'
import { useI18n } from '../../i18n'
import { isProviderEnabled } from '../../utils/providers'
import { adminErrorMessage } from './adminErrors'

export function useProviderRegistry(canWrite, view) {
  const { t } = useI18n()

  const providers = ref([])
  const providersLoading = ref(false)
  const providersError = ref('')

  const currentProviderKey = computed(() => String(view.value?.providerId || ''))

  /** 该供应商是否是「当前生效」的供应商 */
  function providerIsActive(row) {
    if (!currentProviderKey.value || !row?.key) return false
    return row.key.toLowerCase() === currentProviderKey.value.toLowerCase()
  }

  /** 切换模型卡片：停用的供应商不可选（列表接口不可用时按可用处理，保持原行为） */
  function isProviderDisabled(key) {
    if (!key) return false
    const found = providers.value.find(
      (p) => String(p.key || '').toLowerCase() === String(key).toLowerCase()
    )
    return !!found && !isProviderEnabled(found)
  }

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
    load,
    providerIsActive,
    isProviderDisabled
  }
}
