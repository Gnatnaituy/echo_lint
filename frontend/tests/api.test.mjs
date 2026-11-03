/**
 * 前端接线层单测（审计 P3-6）：axios 拦截器 / 401 统一处理 / 403 文案 /
 * blob 下载文件名与错误体 / upload silent 选项 / overview 请求去重。
 *
 * 全部用 axios 自定义 adapter 注入响应，零新依赖、不启动浏览器、不访问网络。
 */
import assert from 'node:assert/strict'
import { afterEach, beforeEach, test } from 'node:test'
import {
  api,
  handleUnauthorized,
  http,
  pickMessage,
  requestBlob,
  unauthorizedDeps
} from '../src/api/index.js'
import { createOverviewState, OVERVIEW_TTL_MS } from '../src/state/overview.js'
import { setLocale, t } from '../src/i18n/index.js'

/** 安装自定义 axios adapter；handler 返回 {status?, data?, headers?}，非 2xx 走响应拦截器的错误分支 */
function installAdapter(handler) {
  const calls = []
  http.defaults.adapter = async (config) => {
    calls.push(config)
    const { status = 200, data = null, headers = {} } = (await handler(config)) || {}
    const response = { data, status, statusText: '', headers, config, request: {} }
    if (status >= 200 && status < 300) return response
    const error = new Error(`Request failed with status code ${status}`)
    error.config = config
    error.response = response
    error.isAxiosError = true
    throw error
  }
  return calls
}

const originalAdapter = http.defaults.adapter

beforeEach(() => setLocale('zh'))

afterEach(() => {
  http.defaults.adapter = originalAdapter
  setLocale('zh')
})

/** 注入 401 处理依赖替身（避免真实 router 依赖 window） */
function stubUnauthorizedDeps({ path = '/recordings', fullPath = '/recordings?page=2' } = {}) {
  const events = { cleared: 0, pushed: [] }
  const original = unauthorizedDeps.load
  unauthorizedDeps.load = async () => ({
    auth: {
      clear: () => {
        events.cleared += 1
      }
    },
    router: {
      currentRoute: { value: { path, fullPath } },
      push: (location) => {
        events.pushed.push(location)
        return Promise.resolve()
      }
    }
  })
  return { events, restore: () => (unauthorizedDeps.load = original) }
}

/* ---------- 401 / 403 ---------- */

test('401：统一清理登录态并跳转登录页（携带原始地址）', async () => {
  const calls = installAdapter(() => ({ status: 401, data: { message: '会话已过期' } }))
  const stub = stubUnauthorizedDeps()
  try {
    await assert.rejects(api.overview(), (error) => {
      assert.equal(error.message, '会话已过期')
      return true
    })
    assert.equal(calls.length, 1)
    assert.equal(stub.events.cleared, 1)
    assert.deepEqual(stub.events.pushed, [
      { path: '/login', query: { redirect: '/recordings?page=2' } }
    ])
  } finally {
    stub.restore()
  }
})

test('401：登录接口 / 会话探测失败不触发全局清理与跳转', async () => {
  installAdapter(() => ({ status: 401, data: { message: '用户名或密码错误' } }))
  let loads = 0
  const original = unauthorizedDeps.load
  unauthorizedDeps.load = async () => {
    loads += 1
    throw new Error('不应触发全局 401 处理')
  }
  try {
    await assert.rejects(api.login({ username: 'a', password: 'b' }), /用户名或密码错误/)
    await assert.rejects(api.me(), /用户名或密码错误/)
    assert.equal(loads, 0)
  } finally {
    unauthorizedDeps.load = original
  }
})

test('handleUnauthorized：已在登录页时不重复跳转，但仍清理登录态', async () => {
  let pushed = 0
  let cleared = 0
  await assert.rejects(
    handleUnauthorized(
      { response: { data: { message: 'expired' } }, message: 'boom' },
      {
        load: async () => ({
          auth: { clear: () => (cleared += 1) },
          router: {
            currentRoute: { value: { path: '/login', fullPath: '/login' } },
            push: () => (pushed += 1)
          }
        })
      }
    ),
    /expired/
  )
  assert.equal(cleared, 1)
  assert.equal(pushed, 0)
})

test('403：统一文案（含 silent 请求），并保留 status', async () => {
  installAdapter(() => ({ status: 403, data: { message: '后端细节不外露' } }))
  await assert.rejects(api.listUsers(), (error) => {
    assert.equal(error.message, t('api.forbidden'))
    assert.equal(error.status, 403)
    return true
  })
  // silent 请求（如任务队列）同样返回统一文案，只由调用方决定是否展示
  installAdapter(() => ({ status: 403, data: {} }))
  await assert.rejects(api.listTasks({ page: 0 }), (error) => {
    assert.equal(error.message, t('api.forbidden'))
    return true
  })
})

test('其它错误：优先取后端 message，并透传 HTTP status', async () => {
  installAdapter(() => ({ status: 500, data: { message: '后端炸了' } }))
  await assert.rejects(api.overview(), (error) => {
    assert.equal(error.message, '后端炸了')
    assert.equal(error.status, 500)
    return true
  })
})

/* ---------- pickMessage：blob 错误体 ---------- */

test('pickMessage：解析 blob 内的 JSON 错误体，非 JSON 回退 axios message', async () => {
  const jsonBlob = new Blob([JSON.stringify({ message: '无权限' })], { type: 'application/json' })
  assert.equal(await pickMessage({ response: { data: jsonBlob }, message: 'fallback' }), '无权限')

  const errorBlob = new Blob([JSON.stringify({ error: '导出失败' })], { type: 'application/json' })
  assert.equal(
    await pickMessage({ response: { data: errorBlob }, message: 'fallback' }),
    '导出失败'
  )

  const htmlBlob = new Blob(['<html>500</html>'], { type: 'text/html' })
  assert.equal(
    await pickMessage({
      response: { data: htmlBlob },
      message: 'Request failed with status code 500'
    }),
    'Request failed with status code 500'
  )

  // 普通 JSON 错误体 / 字符串错误体
  assert.equal(
    await pickMessage({ response: { data: { message: '普通错误' } }, message: 'axios' }),
    '普通错误'
  )
  assert.equal(await pickMessage({ response: { data: '文本错误' }, message: 'axios' }), '文本错误')
  // 无错误体：回退 axios message，再回退通用文案
  assert.equal(await pickMessage({ message: 'network error' }), 'network error')
  assert.equal(await pickMessage({}), t('api.genericError'))
})

/* ---------- requestBlob：文件名 / 错误体 ---------- */

test('requestBlob：解析 Content-Disposition（RFC 5987 中文名）并透传 params', async () => {
  const blob = new Blob(['a,b\n1,2'], { type: 'text/csv' })
  const calls = installAdapter(() => ({
    data: blob,
    headers: {
      'content-disposition':
        'attachment; filename="fallback.csv"; filename*=UTF-8\'\'%E5%BD%95%E9%9F%B3.csv'
    }
  }))
  const result = await requestBlob('/reports/recordings.csv', {
    params: { page: 0 },
    fallbackName: 'fallback-2026-11-03.csv'
  })
  assert.equal(result.blob, blob)
  assert.equal(result.filename, '录音.csv')
  assert.equal(calls[0].url, '/reports/recordings.csv')
  assert.deepEqual(calls[0].params, { page: 0 })
  assert.equal(calls[0].responseType, 'blob')
  assert.equal(calls[0].rawResponse, true)
})

test('requestBlob：无 Content-Disposition 时用兜底文件名', async () => {
  installAdapter(() => ({ data: new Blob(['x'], { type: 'text/csv' }), headers: {} }))
  const result = await requestBlob('/reports/reviews.csv', {
    fallbackName: 'reviews-2026-11-03.csv'
  })
  assert.equal(result.filename, 'reviews-2026-11-03.csv')
})

test('requestBlob：200 + JSON 错误体不能当文件保存', async () => {
  installAdapter(() => ({
    status: 200,
    data: new Blob([JSON.stringify({ message: '导出无权限' })], { type: 'application/json' }),
    headers: {}
  }))
  await assert.rejects(requestBlob('/reports/recordings.csv'), /导出无权限/)
})

test('requestBlob：jsonAsData 时合法 JSON 数组是文件内容，错误对象仍拦截', async () => {
  const corpusBlob = new Blob([JSON.stringify([{ id: 1, label: 'VIOLATION' }])], {
    type: 'application/json'
  })
  installAdapter(() => ({
    data: corpusBlob,
    headers: { 'content-disposition': 'attachment; filename="corpus_20261103_120000.json"' }
  }))
  const result = await requestBlob('/corpus/export', { jsonAsData: true })
  assert.equal(result.blob, corpusBlob)
  assert.equal(result.filename, 'corpus_20261103_120000.json')

  installAdapter(() => ({
    data: new Blob([JSON.stringify({ error: '权限不足' })], { type: 'application/json' }),
    headers: {}
  }))
  await assert.rejects(requestBlob('/corpus/export', { jsonAsData: true }), /权限不足/)
})

test('api.exportCorpus：走统一 requestBlob，返回 { blob, filename }', async () => {
  const blob = new Blob([JSON.stringify([])], { type: 'application/json' })
  installAdapter(() => ({
    data: blob,
    headers: { 'content-disposition': 'attachment; filename="corpus_20261103_235959.json"' }
  }))
  const result = await api.exportCorpus()
  assert.equal(result.blob, blob)
  assert.equal(result.filename, 'corpus_20261103_235959.json')
})

/* ---------- upload：silent 选项向后兼容（P2-33） ---------- */

test('upload：silent 透传请求 config，旧签名保持兼容', async () => {
  const file = new Blob(['audio'], { type: 'audio/wav' })

  const silentCalls = installAdapter(() => ({ status: 200, data: { id: 1 } }))
  await api.upload(file, { agentName: '张三', direction: 'INBOUND' }, { silent: true })
  assert.equal(silentCalls[0].url, '/recordings/upload')
  assert.equal(silentCalls[0].silent, true)
  const fd = silentCalls[0].data
  assert.ok(fd instanceof FormData)
  assert.equal(fd.get('agentName'), '张三')
  assert.equal(fd.get('direction'), 'INBOUND')
  assert.equal(fd.get('callTime'), null)

  // 旧签名：不传 options / 只传进度回调时行为不变（无 silent）
  const legacyCalls = installAdapter(() => ({ status: 200, data: {} }))
  await api.upload(file, {})
  assert.equal(legacyCalls[0].silent, undefined)
  assert.equal(legacyCalls[0].onUploadProgress, undefined)

  const progress = () => {}
  const progressCalls = installAdapter(() => ({ status: 200, data: {} }))
  await api.upload(file, {}, { onUploadProgress: progress })
  assert.equal(progressCalls[0].onUploadProgress, progress)
  assert.equal(progressCalls[0].silent, undefined)

  // silent 失败仍向上抛出可读错误（由调用方行内展示 / 汇总提示）
  installAdapter(() => ({ status: 500, data: { message: '上传失败' } }))
  await assert.rejects(api.upload(file, {}, { silent: true }), /上传失败/)
})

/* ---------- overview 单例：去重 / 缓存 / 失败保留旧数据 ---------- */

test('overview 单例：并发请求去重、TTL 内复用缓存、force 强制刷新', async () => {
  const pending = []
  let clock = 1000
  const store = createOverviewState(
    () =>
      new Promise((resolve, reject) => {
        pending.push({ resolve, reject })
      }),
    { ttlMs: OVERVIEW_TTL_MS, now: () => clock }
  )

  const first = store.load()
  const second = store.load()
  assert.equal(pending.length, 1, '并发调用应复用同一个 in-flight 请求')
  assert.equal(store.loading, true)

  pending[0].resolve({ totalUploads: 10, statusCounts: { NEEDS_REVIEW: 2 } })
  const [a, b] = await Promise.all([first, second])
  assert.deepEqual(a, { totalUploads: 10, statusCounts: { NEEDS_REVIEW: 2 } })
  assert.equal(b, a, '并发调用应拿到同一份数据')
  assert.equal(store.loading, false)
  assert.equal(store.data.totalUploads, 10)

  // TTL 内直接命中缓存
  clock += OVERVIEW_TTL_MS - 1
  assert.equal(await store.load(), a)
  assert.equal(pending.length, 1)

  // 超过 TTL 重新请求
  clock += 2
  const third = store.load()
  assert.equal(pending.length, 2)
  pending[1].resolve({ totalUploads: 20 })
  assert.deepEqual(await third, { totalUploads: 20 })
  assert.equal(store.data.totalUploads, 20)

  // force：忽略缓存
  const fourth = store.load({ force: true })
  assert.equal(pending.length, 3)
  pending[2].resolve({ totalUploads: 30 })
  assert.deepEqual(await fourth, { totalUploads: 30 })
})

test('overview 单例：失败记录 error、保留旧数据、可重试', async () => {
  let fail = false
  let calls = 0
  const store = createOverviewState(() => {
    calls += 1
    return fail
      ? Promise.reject(Object.assign(new Error('overview down'), { status: 500 }))
      : Promise.resolve({ totalUploads: 1 })
  })
  const ok = await store.load()
  assert.deepEqual(ok, { totalUploads: 1 })
  assert.equal(calls, 1)
  assert.equal(store.error, null)

  fail = true
  await assert.rejects(store.load({ force: true }), /overview down/)
  assert.equal(store.error.message, 'overview down')
  assert.equal(store.error.status, 500)
  assert.deepEqual(store.data, { totalUploads: 1 }, '轮询失败应保留旧数据，不清空界面')

  // 后端恢复后重试成功并清除 error
  fail = false
  assert.deepEqual(await store.load({ force: true }), { totalUploads: 1 })
  assert.equal(store.error, null)
  assert.equal(calls, 3)

  // invalidate：让下一次 load 忽略缓存重新请求（写操作 / 登出后使用）
  store.invalidate()
  assert.deepEqual(await store.load(), { totalUploads: 1 })
  assert.equal(calls, 4)
})

test('createOverviewState：缺少 fetcher 时显式报错', () => {
  assert.throws(() => createOverviewState(null), /requires a fetcher function/)
})
