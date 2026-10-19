/**
 * 批量上传队列纯逻辑单测（Batch 4b）
 */
import assert from 'node:assert/strict'
import { test } from 'node:test'
import {
  UPLOAD_STATUS,
  UPLOAD_STATUS_LABEL,
  createUploadQueue,
  isUploadFinished,
  markRemainingCanceled,
  nextPendingIndex,
  overallProgress,
  progressFromEvent,
  summarizeQueue,
  updateQueueItem
} from '../src/utils/uploadQueue.js'

const files = [
  { name: 'a.mp3', size: 100 },
  { name: 'b.wav', size: 300 },
  { name: 'c.m4a', size: 0 }
]

test('创建队列：名称 / 大小 / 初始 pending / uid 唯一', () => {
  const queue = createUploadQueue(files)
  assert.equal(queue.length, 3)
  assert.equal(queue[0].name, 'a.mp3')
  assert.equal(queue[1].size, 300)
  assert.equal(queue[0].status, UPLOAD_STATUS.PENDING)
  assert.equal(queue[0].progress, 0)
  const uids = new Set(queue.map((i) => i.uid))
  assert.equal(uids.size, 3)
  assert.deepEqual(createUploadQueue(null), [])
})

test('不可变更新单项：只改目标项', () => {
  const queue = createUploadQueue(files)
  const next = updateQueueItem(queue, queue[1].uid, { progress: 42, status: UPLOAD_STATUS.UPLOADING })
  assert.equal(next[1].progress, 42)
  assert.equal(next[1].status, UPLOAD_STATUS.UPLOADING)
  assert.equal(next[0].status, UPLOAD_STATUS.PENDING)
  assert.equal(queue[1].progress, 0)
})

test('顺序取下一个待上传项，跳过完成项', () => {
  let queue = createUploadQueue(files)
  assert.equal(nextPendingIndex(queue), 0)
  queue = updateQueueItem(queue, queue[0].uid, { status: UPLOAD_STATUS.SUCCESS })
  assert.equal(nextPendingIndex(queue), 1)
  queue = updateQueueItem(queue, queue[1].uid, { status: UPLOAD_STATUS.ERROR })
  assert.equal(nextPendingIndex(queue), 2)
})

test('取消剩余：仅 pending 变 canceled，上传中/成功不受影响', () => {
  let queue = createUploadQueue(files)
  queue = updateQueueItem(queue, queue[0].uid, { status: UPLOAD_STATUS.SUCCESS })
  queue = updateQueueItem(queue, queue[1].uid, { status: UPLOAD_STATUS.UPLOADING, progress: 30 })
  const canceled = markRemainingCanceled(queue)
  assert.equal(canceled[0].status, UPLOAD_STATUS.SUCCESS)
  assert.equal(canceled[1].status, UPLOAD_STATUS.UPLOADING)
  assert.equal(canceled[2].status, UPLOAD_STATUS.CANCELED)
  assert.equal(canceled[2].error, '已取消')
})

test('汇总：成功/失败/取消计数与完成百分比', () => {
  let queue = createUploadQueue(files)
  queue = updateQueueItem(queue, queue[0].uid, { status: UPLOAD_STATUS.SUCCESS, progress: 100 })
  queue = updateQueueItem(queue, queue[1].uid, { status: UPLOAD_STATUS.ERROR })
  const summary = summarizeQueue(queue)
  assert.equal(summary.total, 3)
  assert.equal(summary.success, 1)
  assert.equal(summary.error, 1)
  assert.equal(summary.pending, 1)
  assert.equal(summary.done, 2)
  assert.equal(summary.percent, 67)
  assert.deepEqual(summarizeQueue(null), {
    total: 0,
    pending: 0,
    uploading: 0,
    success: 0,
    error: 0,
    canceled: 0,
    done: 0,
    percent: 0
  })
})

test('整体进度：按文件大小加权，大小为 0 时退化为平均值', () => {
  let queue = createUploadQueue(files)
  queue = updateQueueItem(queue, queue[0].uid, { progress: 100 })
  queue = updateQueueItem(queue, queue[1].uid, { progress: 50 })
  queue = updateQueueItem(queue, queue[2].uid, { progress: 0 })
  // (100*100 + 300*50 + 0*0) / (100+300) = 62.5 -> 63
  assert.equal(overallProgress(queue), 63)
  assert.equal(overallProgress([]), 0)

  let zeroSize = createUploadQueue([{ name: 'x', size: 0 }, { name: 'y', size: 0 }])
  zeroSize = updateQueueItem(zeroSize, zeroSize[0].uid, { progress: 100 })
  zeroSize = updateQueueItem(zeroSize, zeroSize[1].uid, { progress: 40 })
  assert.equal(overallProgress(zeroSize), 70)
})

test('axios 进度事件：0~100 截断，异常输入安全兜底', () => {
  assert.equal(progressFromEvent({ loaded: 50, total: 200 }), 25)
  assert.equal(progressFromEvent({ loaded: 300, total: 200 }), 100)
  assert.equal(progressFromEvent({ loaded: 10, total: 0 }), 0)
  assert.equal(progressFromEvent(null), 0)
  assert.equal(progressFromEvent({ loaded: 'x', total: 100 }), 0)
})

test('结束判定：全为终态才算结束，空队列不算', () => {
  assert.equal(isUploadFinished([]), false)
  let queue = createUploadQueue(files)
  assert.equal(isUploadFinished(queue), false)
  queue = updateQueueItem(queue, queue[0].uid, { status: UPLOAD_STATUS.SUCCESS })
  queue = updateQueueItem(queue, queue[1].uid, { status: UPLOAD_STATUS.ERROR })
  queue = updateQueueItem(queue, queue[2].uid, { status: UPLOAD_STATUS.CANCELED })
  assert.equal(isUploadFinished(queue), true)
})

test('状态文案映射覆盖全部状态', () => {
  for (const status of Object.values(UPLOAD_STATUS)) {
    assert.ok(UPLOAD_STATUS_LABEL[status], status)
  }
})
