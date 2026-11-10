/**
 * AI 复筛模型（当前生效 / 切换合并为一张卡）共享状态与请求。
 *
 * - view = 当前生效（状态行 + A/B 评测共用）；draft = 网格里选中的供应商与模型。
 * - 状态行「测试连通性」测当前生效，「测试」测待保存选择；两者互斥（testing），结果共用。
 * - 保存成功后同步刷新状态行与 A/B 卡片依赖的 view。
 * 状态由 SettingsLayout.vue 在 setup 中创建后 provide 下发，页面卸载即释放。
 */
import { computed, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../../api'
import { useI18n } from '../../i18n'
import { normalizeScreenModel } from '../../utils/evaluation'

export function useScreenModel() {
  const { t } = useI18n()

  const view = ref(null)
  const draft = reactive({ providerId: '', model: '' })
  const saving = ref(false)
  /** 正在测试的目标：'active' | 'draft' | null */
  const testing = ref(null)
  const testResult = ref(null)

  const currentProvider = computed(
    () => (view.value?.providers || []).find((p) => p.id === draft.providerId) || null
  )

  const dirty = computed(
    () =>
      !!view.value &&
      (draft.providerId !== view.value.providerId || draft.model !== view.value.model)
  )

  async function load() {
    try {
      const data = await api.screenModel()
      view.value = data
      draft.providerId = data.providerId
      draft.model = data.model
    } catch {
      // 请求层已统一提示
    }
  }

  function pickProvider(p) {
    draft.providerId = p.id
    draft.model = p.defaultModel || p.models?.[0] || ''
  }

  async function save() {
    saving.value = true
    testResult.value = null
    try {
      const data = await api.updateScreenModel({ providerId: draft.providerId, model: draft.model })
      view.value = normalizeScreenModel(data)
      draft.providerId = data.providerId
      draft.model = data.model
      ElMessage.success(
        t('settings.model.switched', { provider: data.providerLabel, model: data.model })
      )
    } catch {
      // 请求层已统一提示
    } finally {
      saving.value = false
    }
  }

  async function runTest(target, payload) {
    testing.value = target
    testResult.value = null
    try {
      testResult.value = await api.testScreenModel(payload)
    } catch {
      // 请求层已统一提示
    } finally {
      testing.value = null
    }
  }

  /** 测「当前生效」的模型 */
  function testActive() {
    if (!view.value) return
    runTest('active', { providerId: view.value.providerId, model: view.value.model })
  }

  /** 测「待保存」的选择，便于先验证再切换 */
  function testDraft() {
    if (!draft.providerId) return
    runTest('draft', { providerId: draft.providerId, model: draft.model })
  }

  return {
    view,
    draft,
    saving,
    testing,
    testResult,
    currentProvider,
    dirty,
    load,
    pickProvider,
    save,
    testActive,
    testDraft
  }
}
