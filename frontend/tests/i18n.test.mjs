/**
 * i18n：key 对齐 / 回退 / 插值 / 持久化 / <html lang> 同步
 */
import assert from 'node:assert/strict'
import { test } from 'node:test'
import zh from '../src/i18n/zh.js'
import en from '../src/i18n/en.js'
import {
  LOCALE_STORAGE_KEY,
  MESSAGES,
  detectInitialLocale,
  getLocale,
  interpolate,
  messageKeys,
  setLocale,
  t,
  tn,
  toggleLocale,
  useI18n
} from '../src/i18n/index.js'

function installStorage() {
  const store = new Map()
  globalThis.localStorage = {
    getItem: (key) => (store.has(key) ? store.get(key) : null),
    setItem: (key, value) => store.set(key, String(value)),
    removeItem: (key) => store.delete(key)
  }
  return store
}

function cleanupEnv() {
  delete globalThis.localStorage
  delete globalThis.document
  setLocale('zh')
}

test('zh / en 词典 key 完全对齐', () => {
  assert.deepEqual(messageKeys(en), messageKeys(zh))
  assert.ok(messageKeys(zh).length > 60)
})

test('t：默认中文、未知 key 原样返回、支持 {param} 插值', () => {
  setLocale('zh')
  assert.equal(t('nav.dashboard'), '工作台')
  assert.equal(t('not.exists.key'), 'not.exists.key')
  assert.equal(t('login.welcome', { name: '张三' }), '欢迎回来，张三')
  assert.equal(t('login.welcome', {}), '欢迎回来，{name}')
  assert.equal(interpolate('a {x} b', { x: 1 }), 'a 1 b')
  assert.equal(interpolate('a {x} b', null), 'a {x} b')
})

test('t：英文缺失 key 回退中文（不出现空白）', () => {
  setLocale('en')
  const saved = MESSAGES.en['common.cancel']
  try {
    delete MESSAGES.en['common.cancel']
    assert.equal(t('common.cancel'), '取消')
  } finally {
    MESSAGES.en['common.cancel'] = saved
    setLocale('zh')
  }
})

test('setLocale：非法值回退中文，合法值写入 localStorage 并同步 document.lang', () => {
  const store = installStorage()
  globalThis.document = { documentElement: { lang: '' } }
  try {
    assert.equal(setLocale('en'), 'en')
    assert.equal(store.get(LOCALE_STORAGE_KEY), 'en')
    assert.equal(globalThis.document.documentElement.lang, 'en')
    assert.equal(getLocale(), 'en')
    assert.equal(detectInitialLocale(), 'en')

    assert.equal(setLocale('fr'), 'zh')
    assert.equal(store.get(LOCALE_STORAGE_KEY), 'zh')
    assert.equal(globalThis.document.documentElement.lang, 'zh-CN')

    assert.equal(toggleLocale(), 'en')
    assert.equal(getLocale(), 'en')
  } finally {
    cleanupEnv()
  }
})

test('useI18n：locale 为响应式 computed，t 使用当前语言', () => {
  setLocale('zh')
  const { locale, t: translate } = useI18n()
  assert.equal(locale.value, 'zh')
  assert.equal(translate('nav.review'), '人工复检')
  setLocale('en')
  assert.equal(locale.value, 'en')
  assert.equal(translate('nav.review'), 'Manual Review')
  setLocale('zh')
})

test('词典值非空；英文词典不残留中文（语言名除外）', () => {
  const CJK = /[\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff]/
  // header.languageZh 在英文界面也显示「中文」（切回中文的入口），属于有意保留
  const EN_CJK_ALLOWED = new Set(['header.languageZh'])
  for (const locale of ['zh', 'en']) {
    for (const [key, value] of Object.entries(MESSAGES[locale])) {
      assert.equal(typeof value, 'string', `${locale}.${key} 必须是字符串`)
      assert.ok(value.trim().length > 0, `${locale}.${key} 不能为空`)
      if (locale === 'en' && !EN_CJK_ALLOWED.has(key)) {
        assert.ok(!CJK.test(value), `en.${key} 仍含中文：${value}`)
      }
    }
  }
})

test('源码中静态 t(\'key\') 引用的 key 都存在于词典', async () => {
  const { readdirSync, readFileSync, statSync } = await import('node:fs')
  const { join, resolve } = await import('node:path')
  const { fileURLToPath } = await import('node:url')
  const ROOT = resolve(fileURLToPath(new URL('..', import.meta.url)))
  const SRC = join(ROOT, 'src')
  const I18N_DIR = join(SRC, 'i18n')

  const files = []
  const walk = (dir) => {
    for (const name of readdirSync(dir)) {
      const full = join(dir, name)
      if (statSync(full).isDirectory()) {
        if (full !== I18N_DIR) walk(full)
      } else if (/\.(js|vue)$/.test(name)) {
        files.push(full)
      }
    }
  }
  walk(SRC)

  const keys = new Set()
  const STATIC_T = /(?:^|[^\w.])(?:\$?t)\(\s*(['"])([A-Za-z][\w.-]*)\1/g
  for (const file of files) {
    const source = readFileSync(file, 'utf8')
    for (const match of source.matchAll(STATIC_T)) keys.add(match[2])
  }
  const missing = [...keys].filter((key) => !(key in MESSAGES.zh)).sort()
  assert.deepEqual(missing, [], `源码引用但词典缺失的 key：\n${missing.join('\n')}`)
  assert.ok(keys.size > 100, `静态 t() key 引用数量异常：${keys.size}`)
})

test('tn：复数变体按 count 选择，缺失变体时回退普通 t()', () => {
  setLocale('en')
  assert.equal(tn('time.minutesAgo', 1, { n: 1 }), '1 minute ago')
  assert.equal(tn('time.minutesAgo', 5, { n: 5 }), '5 minutes ago')
  assert.equal(tn('apiKeys.count', 1, { count: 1 }), '1 key')
  assert.equal(tn('apiKeys.count', 2, { count: 2 }), '2 keys')
  assert.equal(tn('common.save', 1), 'Save')
  setLocale('zh')
  assert.equal(tn('time.minutesAgo', 1, { n: 1 }), '1 分钟前')
  assert.equal(tn('time.minutesAgo', 12, { n: 12 }), '12 分钟前')
})
