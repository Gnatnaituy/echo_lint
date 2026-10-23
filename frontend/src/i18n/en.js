/**
 * English messages. Key set must stay in sync with zh.js (tested).
 *
 * 全站英文文案与中文集中维护在 `messages/*.js` 的同名 key 上；
 * English 缺 key 时自动回退中文，避免界面出现空白。
 */
import { mergeMessages } from './messages/index.js'

export default mergeMessages('en')
