/**
 * tracing 页面文案（zh / en 必须一一对应）。
 *
 * 归属：见 docs / i18n README 的「迁移约定」；key 统一使用 `tracing.` 前缀。
 * 页面本身是 Jaeger UI 的同域 iframe（nginx 反代 /jaeger/ + 登录态网关）。
 */
export default {
  zh: {
    'tracing.openNew': '新窗口打开',
    'tracing.viewingTrace': '正在查看指定 trace',
    'tracing.hint':
      'Micrometer Tracing → OTLP → Jaeger；上传请求与流水线执行已通过 traceparent 续接为同一条链路。',
    'tracing.loading': '正在加载 Jaeger…'
  },
  en: {
    'tracing.openNew': 'Open in new window',
    'tracing.viewingTrace': 'Viewing a specific trace',
    'tracing.hint':
      'Micrometer Tracing → OTLP → Jaeger; the upload request and the pipeline run are stitched into one trace via traceparent.',
    'tracing.loading': 'Loading Jaeger…'
  }
}
