/**
 * 通用响应形状归一化工具。
 *
 * 前端与后端并行开发时字段可能增补 / 重命名 / 暂缺，所有归一化函数都通过这里取值：
 * - 缺失字段返回 null / 0 / []，界面据此显示「未知 / 未配置」，不臆造业务值
 * - 不做业务默认值判断（如「未返回 enabled 时是否启用」），由各调用方按场景决定
 */

export function asObject(value) {
  return value && typeof value === 'object' && !Array.isArray(value) ? value : {}
}

/** 依次取第一个非 null/undefined 的字段，兼容后端字段重命名 */
export function pick(source, keys) {
  const data = asObject(source)
  for (const key of keys) {
    if (data[key] !== undefined && data[key] !== null) return data[key]
  }
  return undefined
}

/** 宽松布尔：true/false、1/0、'true'/'false' 可识别，其余返回 null（未知） */
export function toBoolean(value) {
  if (value === undefined || value === null || value === '') return null
  if (typeof value === 'boolean') return value
  if (value === 1 || value === '1' || value === 'true' || value === 'TRUE') return true
  if (value === 0 || value === '0' || value === 'false' || value === 'FALSE') return false
  return null
}

/** 宽松数字：非数字 / 空值返回 null */
export function toNumber(value) {
  if (value === undefined || value === null || value === '') return null
  const num = Number(value)
  return Number.isFinite(num) ? num : null
}

/** 宽松文本：null/undefined -> ''，其余 trim 后的字符串 */
export function toText(value) {
  if (value === undefined || value === null) return ''
  return typeof value === 'string' ? value.trim() : String(value).trim()
}

/**
 * 字符串列表：兼容字符串数组 / 逗号分隔字符串 / {value,label} 对象数组，
 * 去空白、去重、丢弃空值。
 */
export function toStringList(value) {
  let raw = []
  if (Array.isArray(value)) raw = value
  else if (typeof value === 'string') raw = value.split(',')
  const out = []
  for (const item of raw) {
    let text = ''
    if (typeof item === 'string') text = item.trim()
    else if (item && typeof item === 'object') {
      text = toText(item.value ?? item.name ?? item.label ?? item.model ?? '')
    }
    if (text && !out.includes(text)) out.push(text)
  }
  return out
}
