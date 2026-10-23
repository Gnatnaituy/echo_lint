/**
 * i18n 覆盖守卫：`src/` 下除注释、`src/i18n/**` 词典外，不允许残留硬编码中文。
 *
 * 迁移说明与豁免规则见 `src/i18n/README.md`；确实不可翻译的中文字面量（如持久化到
 * 后端的分类取值）在该行加 `// i18n-audit-ignore`。
 */
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { audit } from '../scripts/i18n-audit.mjs'

test('内容源码无硬编码中文（注释 / 词典 / 显式豁免除外）', () => {
  const { reports, total } = audit()
  const detail = reports
    .filter((report) => report.hits.length)
    .map(
      (report) =>
        `${report.file.replace(/^.*?src\//, 'src/')}\n` +
        report.hits.map((hit) => `  ${hit.line}:${hit.col}  ${hit.snippet}`).join('\n')
    )
    .join('\n')
  assert.equal(total, 0, `仍有硬编码中文：\n${detail}`)
})
