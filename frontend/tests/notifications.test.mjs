/**
 * 运营通知（Webhook）归一化 / 抽样标记 / 审计文案单测：node --test frontend/tests/
 */
import assert from 'node:assert/strict'
import { test } from 'node:test'
import {
  AUDIT_ACTION_META,
  AUDIT_ACTION_OPTIONS,
  NOTIFY_EVENT_META,
  auditActionLabel,
  isSampled,
  notifyEventMeta
} from '../src/constants.js'
import {
  formatSamplingRate,
  normalizeEvents,
  normalizeNotificationSettings,
  normalizeNotificationTest,
  normalizeSamplingRate,
  toBoolean,
  toNumber
} from '../src/utils/notifications.js'

test('通知设置：契约字段完整归一化，抽样比例缺失时为 null（不臆造）', () => {
  const settings = normalizeNotificationSettings({
    enabled: true,
    urlConfigured: true,
    backlogThreshold: 50,
    events: ['REVIEW_CONFIRMED_VIOLATION', 'RECORDING_FAILED', 'REVIEW_BACKLOG', 'NOTIFY_TEST']
  })
  assert.deepEqual(settings, {
    enabled: true,
    urlConfigured: true,
    backlogThreshold: 50,
    samplingRate: null,
    events: ['REVIEW_CONFIRMED_VIOLATION', 'RECORDING_FAILED', 'REVIEW_BACKLOG', 'NOTIFY_TEST']
  })
})

test('通知设置：兼容后端字段重命名 / 字符串布尔 / 逗号事件 / samplingRate', () => {
  const settings = normalizeNotificationSettings({
    webhookEnabled: 'true',
    webhookUrlConfigured: 'false',
    threshold: '80',
    eventTypes: 'REVIEW_BACKLOG,NOTIFY_TEST, REVIEW_BACKLOG,',
    dfaMissRate: '0.25'
  })
  assert.equal(settings.enabled, true)
  assert.equal(settings.urlConfigured, false)
  assert.equal(settings.backlogThreshold, 80)
  assert.equal(settings.samplingRate, 0.25)
  assert.deepEqual(settings.events, ['REVIEW_BACKLOG', 'NOTIFY_TEST'])
  // 兼容 nested sampling 对象
  assert.equal(
    normalizeNotificationSettings({ sampling: { dfaMissRate: '0.5' } }).samplingRate,
    0.5
  )
})

test('通知设置：null / 非对象 / 缺字段全部安全兜底', () => {
  for (const raw of [null, undefined, 'oops', 42, [], {}]) {
    const settings = normalizeNotificationSettings(raw)
    assert.equal(settings.enabled, null)
    assert.equal(settings.urlConfigured, null)
    assert.equal(settings.backlogThreshold, null)
    assert.equal(settings.samplingRate, null)
    assert.deepEqual(settings.events, [])
  }
})

test('事件归一化：对象数组 / 去重 / 丢弃空值', () => {
  assert.deepEqual(
    normalizeEvents([{ event: 'REVIEW_BACKLOG' }, { name: 'NOTIFY_TEST' }, { code: 'X' }, {}, null, '']),
    ['REVIEW_BACKLOG', 'NOTIFY_TEST', 'X']
  )
  assert.deepEqual(normalizeEvents(undefined), [])
  assert.deepEqual(normalizeEvents([]), [])
})

test('抽样比例：仅接受 0~1，越界 / 非数字视为无效', () => {
  assert.equal(normalizeSamplingRate(0), 0)
  assert.equal(normalizeSamplingRate('0.1'), 0.1)
  assert.equal(normalizeSamplingRate(1), 1)
  assert.equal(normalizeSamplingRate(1.5), null)
  assert.equal(normalizeSamplingRate(-0.1), null)
  assert.equal(normalizeSamplingRate('abc'), null)
  assert.equal(normalizeSamplingRate(null), null)
  assert.equal(normalizeSamplingRate(undefined), null)
})

test('抽样比例展示：0% / 5% / 12.5% / 100%，无效值显示占位', () => {
  assert.equal(formatSamplingRate(0), '0%')
  assert.equal(formatSamplingRate(0.05), '5%')
  assert.equal(formatSamplingRate('0.125'), '12.5%')
  assert.equal(formatSamplingRate(1), '100%')
  assert.equal(formatSamplingRate(null), '—')
  assert.equal(formatSamplingRate(undefined), '—')
  assert.equal(formatSamplingRate(2), '—')
  assert.equal(formatSamplingRate('oops'), '—')
})

test('测试结果归一化：ok / latencyMs / message，缺字段兜底', () => {
  assert.deepEqual(normalizeNotificationTest({ ok: true, latencyMs: 42, message: '收到' }), {
    ok: true,
    latencyMs: 42,
    message: '收到'
  })
  // 兼容 success 字段与字符串耗时
  assert.deepEqual(normalizeNotificationTest({ success: 'true', latency: '88', detail: 'sent' }), {
    ok: true,
    latencyMs: 88,
    message: 'sent'
  })
  assert.deepEqual(normalizeNotificationTest({ ok: false }), {
    ok: false,
    latencyMs: null,
    message: ''
  })
  assert.deepEqual(normalizeNotificationTest(null), { ok: false, latencyMs: null, message: '' })
})

test('通用归一化：toBoolean / toNumber', () => {
  assert.equal(toBoolean(true), true)
  assert.equal(toBoolean(1), true)
  assert.equal(toBoolean('TRUE'), true)
  assert.equal(toBoolean('false'), false)
  assert.equal(toBoolean(0), false)
  assert.equal(toBoolean(''), null)
  assert.equal(toBoolean('yes'), null)
  assert.equal(toNumber('0'), 0)
  assert.equal(toNumber(''), null)
  assert.equal(toNumber('x'), null)
})

test('审计动作：NOTIFY_TEST 有中文文案并出现在筛选选项中', () => {
  assert.equal(auditActionLabel('NOTIFY_TEST'), '测试通知')
  assert.equal(AUDIT_ACTION_META.NOTIFY_TEST.type, 'info')
  assert.ok(AUDIT_ACTION_OPTIONS.some((opt) => opt.value === 'NOTIFY_TEST'))
})

test('通知事件文案：已知事件中文标签，未知事件回退原始 code', () => {
  assert.equal(notifyEventMeta('REVIEW_CONFIRMED_VIOLATION').label, '复核确认违规')
  assert.equal(notifyEventMeta('RECORDING_FAILED').label, '录音处理失败')
  assert.equal(notifyEventMeta('REVIEW_BACKLOG').label, '待复核积压')
  assert.equal(notifyEventMeta('NOTIFY_TEST').label, '测试事件')
  assert.equal(notifyEventMeta('SOMETHING_NEW').label, 'SOMETHING_NEW')
  assert.equal(notifyEventMeta(undefined).label, '—')
  assert.deepEqual(Object.keys(NOTIFY_EVENT_META).sort(), [
    'NOTIFY_TEST',
    'RECORDING_FAILED',
    'REVIEW_BACKLOG',
    'REVIEW_CONFIRMED_VIOLATION'
  ])
})

test('抽样标记：boolean / 0-1 / 字符串兼容，缺省视为未抽样', () => {
  assert.equal(isSampled({ sampled: true }), true)
  assert.equal(isSampled({ sampled: 1 }), true)
  assert.equal(isSampled({ sampled: '1' }), true)
  assert.equal(isSampled({ sampled: 'true' }), true)
  assert.equal(isSampled({ sampled: 'TRUE' }), true)
  assert.equal(isSampled({ sampled: false }), false)
  assert.equal(isSampled({ sampled: 0 }), false)
  assert.equal(isSampled({ sampled: 'false' }), false)
  assert.equal(isSampled({ sampled: null }), false)
  assert.equal(isSampled({}), false)
  assert.equal(isSampled(null), false)
  assert.equal(isSampled(undefined), false)
})
