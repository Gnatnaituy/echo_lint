#!/usr/bin/env node
/**
 * i18n 覆盖审计：扫描 `src/` 下「非注释、非词典」的中文字面量。
 *
 * 用途：内容页 i18n 迁移完成后，确保没有遗漏的硬编码中文（注释、`src/i18n/**` 词典除外）。
 * 数据值（如持久化到后端的分类中文）等确实不能翻译的字面量，可在该行加
 * `i18n-audit-ignore` 注释显式豁免。
 *
 * 用法：
 *   node scripts/i18n-audit.mjs               # 扫描全部 src
 *   node scripts/i18n-audit.mjs views/Settings.vue utils/rag.js  # 只扫描路径包含这些片段的文件
 *
 * 退出码：发现遗漏中文时为 1，否则 0。
 */
import { readdirSync, readFileSync, statSync } from 'node:fs'
import { join, relative, resolve, sep } from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = resolve(fileURLToPath(new URL('..', import.meta.url)))
const SRC = join(ROOT, 'src')
const I18N_DIR = join(SRC, 'i18n')
const CJK = /[\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff]/

/** 递归收集 src 下的 .js / .vue 文件（跳过 i18n 词典目录） */
export function collectFiles(dir = SRC) {
  const out = []
  for (const name of readdirSync(dir)) {
    const full = join(dir, name)
    const info = statSync(full)
    if (info.isDirectory()) {
      if (full === I18N_DIR) continue
      out.push(...collectFiles(full))
    } else if (/\.(js|mjs|vue)$/.test(name)) {
      out.push(full)
    }
  }
  return out.sort()
}

/** 把一段文本按「代码 / 字符串 / 注释」分词，注释字符替换为空格（保留行号） */
function maskComments(source) {
  const out = source.split('')
  const n = source.length
  let i = 0
  let state = 'code'
  let prevSignificant = ''
  let templateDepth = 0
  const blank = (index) => {
    if (out[index] !== '\n') out[index] = ' '
  }
  while (i < n) {
    const ch = source[i]
    const next = source[i + 1]

    if (state === 'line') {
      if (ch === '\n') {
        state = 'code'
      } else {
        blank(i)
      }
      i += 1
      continue
    }
    if (state === 'block') {
      if (ch === '*' && next === '/') {
        blank(i)
        blank(i + 1)
        i += 2
        state = 'code'
        continue
      }
      blank(i)
      i += 1
      continue
    }
    if (state === 'single') {
      if (ch === '\\') {
        i += 2
        continue
      }
      if (ch === "'") state = 'code'
      i += 1
      continue
    }
    if (state === 'double') {
      if (ch === '\\') {
        i += 2
        continue
      }
      if (ch === '"') state = 'code'
      i += 1
      continue
    }
    if (state === 'template') {
      if (ch === '\\') {
        i += 2
        continue
      }
      if (ch === '`') state = 'code'
      i += 1
      continue
    }
    if (state === 'regex') {
      if (ch === '\\') {
        i += 2
        continue
      }
      if (ch === '[') templateDepth += 1
      else if (ch === ']') templateDepth = Math.max(0, templateDepth - 1)
      else if (ch === '/' && templateDepth === 0) {
        state = 'code'
        i += 1
        // 跳过 flags
        while (i < n && /[a-z]/i.test(source[i])) i += 1
        continue
      }
      i += 1
      continue
    }

    // code
    if (ch === '/' && next === '/') {
      blank(i)
      blank(i + 1)
      i += 2
      state = 'line'
      continue
    }
    if (ch === '/' && next === '*') {
      blank(i)
      blank(i + 1)
      i += 2
      state = 'block'
      continue
    }
    if (ch === "'") {
      state = 'single'
      i += 1
      continue
    }
    if (ch === '"') {
      state = 'double'
      i += 1
      continue
    }
    if (ch === '`') {
      state = 'template'
      i += 1
      continue
    }
    if (ch === '/' && (prevSignificant === '' || '([{=,:;!&|?+-*%<>~^'.includes(prevSignificant))) {
      // 粗判正则字面量：前一个有效字符是运算符/开括号/代码起点
      state = 'regex'
      templateDepth = 0
      i += 1
      continue
    }
    if (!/\s/.test(ch)) prevSignificant = ch
    i += 1
  }
  return out.join('')
}

/** 保留 HTML 注释以外的模板内容（注释替换为空格） */
function maskHtmlComments(source) {
  return source.replace(/<!--[\s\S]*?-->/g, (match) => match.replace(/[^\n]/g, ' '))
}

/** 把 SFC 拆成 script / template / style 三个区域（style 忽略） */
function splitSfc(source) {
  const regions = []
  const scriptRe = /<script\b[^>]*>([\s\S]*?)<\/script>/gi
  const styleRe = /<style\b[^>]*>([\s\S]*?)<\/style>/gi
  let rest = source
  let match

  const fits = (text, start, end) => ({
    text,
    start,
    end
  })

  while ((match = scriptRe.exec(source))) {
    regions.push(fits(match[1], match.index + match[0].indexOf('>') + 1, match.index + match[0].length))
  }
  while ((match = styleRe.exec(source))) {
    regions.push(fits(match[1], match.index + match[0].indexOf('>') + 1, match.index + match[0].length))
  }
  // template = 原文去掉 script/style 块
  const cut = source.replace(scriptRe, (m) => m.replace(/[^\n]/g, ' ')).replace(styleRe, (m) => m.replace(/[^\n]/g, ' '))
  return { template: cut, scripts: regions }
}

/** 从一行文本里找出中文命中（ignoreLines 为原文中含 i18n-audit-ignore 的行号，1 基） */
function findHits(text, offsetLineStart, ignoreLines = new Set()) {
  const hits = []
  const lines = text.split('\n')
  for (let idx = 0; idx < lines.length; idx += 1) {
    const line = lines[idx]
    if (!CJK.test(line)) continue
    if (ignoreLines.has(offsetLineStart + idx + 1)) continue
    const col = line.search(CJK) + 1
    hits.push({
      line: offsetLineStart + idx + 1,
      col,
      snippet: line.trim().slice(0, 140)
    })
  }
  return hits
}

/** 审计单个文件，返回 { file, hits } */
export function auditFile(file) {
  const source = readFileSync(file, 'utf8')
  // 豁免标记要按「原文」行号收集：注释在后续 mask 中会被替换成空格
  const ignoreLines = new Set(
    source
      .split('\n')
      .map((line, index) => (line.includes('i18n-audit-ignore') ? index + 1 : 0))
      .filter(Boolean)
  )
  let inspected = ''
  if (file.endsWith('.vue')) {
    const { template, scripts } = splitSfc(source)
    // 正文模板：只保留非注释文本（行号与原文一致，script 块已被替换为空格）
    const templateLines = maskHtmlComments(template).split('\n')
    const scriptTexts = scripts.map(({ text, start }) => {
      const before = source.slice(0, start)
      const lineStart = before.split('\n').length
      return { masked: maskComments(text), lineStart }
    })
    // 模板区域：逐行检查（script/style 已被替换成空白，不会重复命中）
    const templateMasked = templateLines.join('\n')
    const hits = findHits(templateMasked, 0, ignoreLines)
    // script 区域单独检查（模板中已替换为空白，重复检查只会命中同一行的真实 script 内容）
    for (const { masked, lineStart } of scriptTexts) {
      hits.push(...findHits(masked, lineStart - 1, ignoreLines))
    }
    inspected = `${templateMasked}\n${scriptTexts.map((s) => s.masked).join('\n')}`
    // 去重（模板遮罩后 script 行是空白，但 split 后行号可能位移，按行号+列号去重）
    const seen = new Map()
    for (const hit of hits) {
      const key = `${hit.line}:${hit.col}:${hit.snippet}`
      if (!seen.has(key)) seen.set(key, hit)
    }
    return { file, hits: [...seen.values()].sort((a, b) => a.line - b.line || a.col - b.col) }
  }
  inspected = maskComments(source)
  return { file, hits: findHits(inspected, 0, ignoreLines) }
}

export function audit(filters = []) {
  const files = collectFiles().filter((file) =>
    filters.length === 0
      ? true
      : filters.some((fragment) => relative(SRC, file).split(sep).join('/').includes(fragment))
  )
  const reports = files.map(auditFile)
  const total = reports.reduce((sum, report) => sum + report.hits.length, 0)
  return { reports, total }
}

const isMain = process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)
if (isMain) {
  const { reports, total } = audit(process.argv.slice(2))
  for (const { file, hits } of reports) {
    if (!hits.length) continue
    process.stdout.write(`\n${relative(ROOT, file)} (${hits.length})\n`)
    for (const hit of hits) {
      process.stdout.write(`  ${hit.line}:${hit.col}  ${hit.snippet}\n`)
    }
  }
  const scanned = reports.length
  if (total === 0) {
    process.stdout.write(`✔ i18n audit: ${scanned} files, no hardcoded Chinese outside i18n dictionaries.\n`)
    process.exit(0)
  }
  process.stdout.write(`\n✖ i18n audit: ${total} hardcoded Chinese line(s) in ${scanned} files.\n`)
  process.exit(1)
}
