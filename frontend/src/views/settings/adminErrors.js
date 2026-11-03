/**
 * 管理员接口错误文案：区分 403/404，避免把后端未上线误报成普通失败。
 * 供应商 / RAG / 通知面板共用。
 */
import { t } from '../../i18n/index.js'

export function adminErrorMessage(error, fallback) {
  const status = error?.status
  if (status === 403) return t('settings.error.forbidden')
  if (status === 404) return t('settings.error.notFound')
  return error?.message || fallback
}
