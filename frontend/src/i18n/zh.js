/**
 * 中文文案（默认 / 回退语言）。
 *
 * 由 `messages/index.js` 汇总各业务模块（每个模块同时维护 zh / en），
 * 未命中的 key 会回退到本词典；本词典也没有时原样返回 key 便于发现漏翻译。
 * 迁移约定见 `src/i18n/README.md`。
 */
import { mergeMessages } from './messages/index.js'

export default mergeMessages('zh')
