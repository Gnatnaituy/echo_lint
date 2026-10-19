/**
 * 下载辅助 / 录音方向元数据单测：node --test frontend/tests/
 */
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { dateStamp, filenameFromDisposition } from '../src/utils/download.js'
import { DIRECTION_META, DIRECTION_OPTIONS, directionLabel, directionMeta } from '../src/constants.js'

test('文件名：普通 filename 带引号 / 不带引号', () => {
  assert.equal(
    filenameFromDisposition('attachment; filename="recordings-20261020.csv"'),
    'recordings-20261020.csv'
  )
  assert.equal(
    filenameFromDisposition('attachment; filename=violations-20261020.csv'),
    'violations-20261020.csv'
  )
})

test('文件名：RFC 5987 编码优先并解码中文', () => {
  const header = "attachment; filename=\"fallback.csv\"; filename*=UTF-8''%E5%BD%95%E9%9F%B3.csv"
  assert.equal(filenameFromDisposition(header), '录音.csv')
})

test('文件名：编码非法时回退 filename=；无头时用兜底名', () => {
  assert.equal(
    filenameFromDisposition("attachment; filename=\"ok.csv\"; filename*=UTF-8''%E5%BD"),
    'ok.csv'
  )
  assert.equal(filenameFromDisposition('attachment'), 'download.csv')
  assert.equal(filenameFromDisposition(null), 'download.csv')
  assert.equal(filenameFromDisposition('', 'reviews-2026-10-20.csv'), 'reviews-2026-10-20.csv')
})

test('日期戳：本地日期补零', () => {
  assert.equal(dateStamp(new Date(2026, 0, 5)), '2026-01-05')
  assert.equal(dateStamp(new Date(2026, 9, 20, 23, 59)), '2026-10-20')
  // 非法入参回退为当前日期，只校验格式
  assert.match(dateStamp('not-a-date'), /^\d{4}-\d{2}-\d{2}$/)
})

test('方向：中文文案 / 选项完整 / 未知值回退', () => {
  assert.equal(directionLabel('INBOUND'), '呼入')
  assert.equal(directionLabel('OUTBOUND'), '呼出')
  assert.equal(directionLabel('UNKNOWN'), '未知')
  assert.equal(directionLabel(null), '—')
  assert.equal(directionLabel('SIDEWAYS'), 'SIDEWAYS')
  assert.deepEqual(
    DIRECTION_OPTIONS.map((o) => o.value),
    ['INBOUND', 'OUTBOUND', 'UNKNOWN']
  )
  assert.equal(directionMeta('INBOUND').type, 'brand')
  assert.equal(directionMeta('nope'), null)
  assert.equal(DIRECTION_META.UNKNOWN.label, '未知')
})
