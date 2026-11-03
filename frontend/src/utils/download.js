/**
 * CSV / Blob 下载辅助（纯函数与最小 DOM 副作用分离，便于单测）
 *
 * 背景：导出接口用 axios responseType:'blob'，失败时后端仍可能返回 JSON 错误体
 * （被包成 Blob），若不解析就会被当成 CSV 保存。这里负责：
 * - 从 Content-Disposition 解析文件名（兼容 RFC 5987 的 filename*=UTF-8''...）
 * - 识别 JSON 错误 Blob 并取出 message
 * - 触发浏览器保存（createObjectURL + a[download]，用完释放）
 */

/** 本地日期戳 YYYY-MM-DD，用于后端未给 Content-Disposition 时的兜底文件名 */
export function dateStamp(date = new Date()) {
  const d = date instanceof Date && !Number.isNaN(date.getTime()) ? date : new Date()
  const pad = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

/**
 * 解析下载文件名：
 * - `attachment; filename="recordings-20261020.csv"`
 * - `attachment; filename*=UTF-8''%E5%BD%95%E9%9F%B3.csv`
 * 解析失败返回 fallback。
 */
export function filenameFromDisposition(disposition, fallback = 'download.csv') {
  const header = typeof disposition === 'string' ? disposition.trim() : ''
  if (!header) return fallback

  // RFC 5987：filename*=charset'lang'percent-encoded
  const encoded = header.match(/filename\*\s*=\s*([\w!#$&+.^`|~-]*)'[^']*'([^;]+)/i)
  if (encoded) {
    const raw = encoded[2].trim().replace(/^"|"$/g, '')
    try {
      const decoded = decodeURIComponent(raw)
      if (decoded) return decoded
    } catch {
      // 编码非法：回退到 filename= 分支
    }
  }

  // 普通 filename=，支持带引号与不带引号
  const plain = header.match(/filename\s*=\s*(?:"([^"]*)"|([^;]+))/i)
  if (plain) {
    const value = (plain[1] ?? plain[2] ?? '').trim()
    if (value) return value
  }

  return fallback
}

/** JSON 错误体解析上限：超过该大小按成功文件处理，避免把大导出整块读进内存再丢弃 */
const JSON_ERROR_MAX_BYTES = 1024 * 1024

/**
 * 若 Blob 实为 JSON 错误体（例如 200 + {"message":"无权限"}），返回其中的 message；
 * 正常文件（text/csv 等）返回空串，调用方据此决定是否保存文件。
 *
 * @param {Blob} blob 下载接口的响应体
 * @param {{jsonAsData?: boolean}} [options] jsonAsData=true 表示该接口成功时本身就是 JSON
 *        （如语料导出）：合法 JSON 数组视为文件内容，只有错误对象（{message}/{error}）才拦截
 */
export async function blobErrorMessage(blob, { jsonAsData = false } = {}) {
  if (!blob || typeof blob.type !== 'string' || !blob.type.toLowerCase().includes('json')) return ''
  if (jsonAsData && Number(blob.size) > JSON_ERROR_MAX_BYTES) return ''
  try {
    const text = (await blob.text()).trim()
    if (!text) return ''
    const data = JSON.parse(text)
    // 合法导出内容（语料导出为 JSON 数组）：不是错误体
    if (jsonAsData && Array.isArray(data)) return ''
    if (typeof data === 'string') return data
    return data?.message || data?.error || ''
  } catch {
    return ''
  }
}

/** 浏览器保存 Blob（CSV）：临时链接 + a[download]，延迟释放避免下载被取消 */
export function saveBlob(blob, filename = 'download.csv') {
  if (!blob || typeof document === 'undefined' || typeof URL === 'undefined') return false
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = filename || 'download.csv'
  link.style.display = 'none'
  document.body.appendChild(link)
  link.click()
  link.remove()
  setTimeout(() => URL.revokeObjectURL(url), 4000)
  return true
}
