/**
 * tasks 页面文案（zh / en 必须一一对应）。
 *
 * 归属：见 docs / i18n README 的「迁移约定」；key 统一使用 `tasks.` 前缀。
 */
export default {
  zh: {
    'tasks.title': '任务队列',
    'tasks.refreshFailed': '刷新失败',
    'tasks.allStatuses': '全部状态',
    'tasks.recordingIdPlaceholder': '录音 ID',
    'tasks.search': '查询',
    'tasks.autoRefresh': '每 {seconds} 秒自动刷新',
    'tasks.lastUpdated': '最后更新 {time}',
    'tasks.queueTip':
      '上传 / 重试录音会入队执行；失败按 30s / 120s / 300s 退避重试，达到最大次数进入死信（最终失败），可手动重试。',

    // 列表列
    'tasks.recording': '录音',
    'tasks.recordingNumber': '录音 #{id}',
    'tasks.taskNumber': '任务 #{id}',
    'tasks.attempts': '尝试',
    'tasks.nextAttempt': '下次尝试',
    'tasks.lastError': '最近错误',
    'tasks.emptyFilteredTitle': '没有匹配的任务',
    'tasks.emptyTitle': '暂无任务',
    'tasks.emptyFilteredHint': '试试清空状态或录音 ID 筛选',
    'tasks.emptyHint': '上传或重试录音后，流水线任务会在这里排队执行',

    // 操作
    'tasks.retryConfirm': '重试会重新排队并将尝试次数归零，确认重试？',
    'tasks.retry': '重试',

    // 错误 / 反馈
    'tasks.loadFailed': '任务列表加载失败，请稍后重试',
    'tasks.forbidden': '当前账号无权限访问任务队列（需要 ADMIN）',
    'tasks.notAvailable': '后端尚未提供任务队列接口（HTTP 404），请确认后端版本',
    'tasks.backgroundFailed': '自动刷新失败，请检查后端服务',
    'tasks.retryFailed': '重试失败，请稍后重试',
    'tasks.retried': '任务 #{id} 已重新排队'
  },
  en: {
    'tasks.title': 'Task Queue',
    'tasks.refreshFailed': 'Refresh failed',
    'tasks.allStatuses': 'All statuses',
    'tasks.recordingIdPlaceholder': 'Recording ID',
    'tasks.search': 'Search',
    'tasks.autoRefresh': 'Auto refresh every {seconds}s',
    'tasks.lastUpdated': 'Last updated {time}',
    'tasks.queueTip':
      'Uploading or retrying a recording enqueues a pipeline task. Failures retry with 30s / 120s / 300s backoff; after the maximum attempts the task becomes a dead letter (final failure) and can be retried manually.',

    // Table columns
    'tasks.recording': 'Recording',
    'tasks.recordingNumber': 'Recording #{id}',
    'tasks.taskNumber': 'Task #{id}',
    'tasks.attempts': 'Attempts',
    'tasks.nextAttempt': 'Next attempt',
    'tasks.lastError': 'Last error',
    'tasks.emptyFilteredTitle': 'No matching tasks',
    'tasks.emptyTitle': 'No tasks yet',
    'tasks.emptyFilteredHint': 'Try clearing the status or recording ID filter',
    'tasks.emptyHint': 'Pipeline tasks appear here after recordings are uploaded or retried',

    // Actions
    'tasks.retryConfirm': 'Retrying requeues the task and resets its attempt count. Retry?',
    'tasks.retry': 'Retry',

    // Errors / feedback
    'tasks.loadFailed': 'Failed to load tasks. Please try again later.',
    'tasks.forbidden': 'This account cannot access the task queue (ADMIN required)',
    'tasks.notAvailable':
      'The backend does not provide the task queue endpoint yet (HTTP 404). Check the backend version.',
    'tasks.backgroundFailed': 'Auto refresh failed. Please check the backend service.',
    'tasks.retryFailed': 'Retry failed. Please try again later.',
    'tasks.retried': 'Task #{id} requeued'
  }
}
