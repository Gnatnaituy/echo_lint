/**
 * 文案模块注册表。
 *
 * 每个模块导出 `{ zh, en }`，按数组顺序合并（同 key 后出现的覆盖先出现的）。
 * 新增页面文案时：新建 `messages/<domain>.js`，在 `MESSAGE_MODULES` 末尾登记即可。
 * 业务域内的 key 必须带域名前缀，避免跨域 key 互相覆盖。
 */
import base from './base.js'
import meta from './meta.js'
import dashboard from './dashboard.js'
import recordings from './recordings.js'
import components from './components.js'
import review from './review.js'
import reports from './reports.js'
import dictionary from './dictionary.js'
import corpus from './corpus.js'
import knowledge from './knowledge.js'
import knowledgeIndex from './knowledgeIndex.js'
import settings from './settings.js'
import costs from './costs.js'
import tasks from './tasks.js'
import aiChat from './aiChat.js'
import apiKeys from './apiKeys.js'
import users from './users.js'
import audit from './audit.js'
import screen from './screen.js'

export const MESSAGE_MODULES = [
  base,
  meta,
  dashboard,
  recordings,
  components,
  review,
  reports,
  dictionary,
  corpus,
  knowledge,
  knowledgeIndex,
  settings,
  costs,
  tasks,
  aiChat,
  apiKeys,
  users,
  audit,
  screen
]

/** 合并指定语言的词典（zh / en） */
export function mergeMessages(locale) {
  return MESSAGE_MODULES.reduce((acc, mod) => Object.assign(acc, mod[locale] || {}), {})
}
