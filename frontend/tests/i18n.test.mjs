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
