/**
 * 逐句转写映射逻辑单测：node --test frontend/tests/
 */
import assert from 'node:assert/strict'
import { test } from 'node:test'
import {
  buildSentenceView,
  findActiveIndex,
  formatClock,
  sideOfChannel,
  splitByHits,
  splitSentences,
  stripSpeakerTag
} from '../src/utils/segments.js'

const TEXT =
  'Thank you for holding. I already explained the policy twice. If you keep pushing me, you stupid bitch, ' +
  'I will kill you, do you understand? Do not call again.'

// 模拟 Whisper verbose_json 的 segments（文本为全文子串，带前导空格）
const SEGMENTS = [
  { id: 0, start: 0, end: 2.4, text: ' Thank you for holding.' },
  { id: 1, start: 2.4, end: 6.1, text: ' I already explained the policy twice.' },
  {
    id: 2,
    start: 6.1,
    end: 12.8,
    text: ' If you keep pushing me, you stupid bitch, I will kill you, do you understand?'
  },
  { id: 3, start: 12.8, end: 15.2, text: ' Do not call again.' }
]

// 命中区间按全文实际位置计算（DFA 命中的 start/end 即全文偏移）
const span = (literal) => {
  const start = TEXT.indexOf(literal)
  assert.ok(start >= 0, `测试文本中找不到「${literal}」`)
  return { start, end: start + literal.length }
}

const HITS = [
  { word: 'stupid', ...span('stupid') },
  { word: 'bitch', ...span('bitch') },
  { word: 'killyou', ...span('kill you') } // 词典词是规范化形式，跨度覆盖原文 "kill you"
]

/** 在任意文本中定位词面（用于构造命中） */
function spanOf(text, literal) {
  const start = text.indexOf(literal)
  assert.ok(start >= 0, `文本中找不到「${literal}」`)
  return { start, end: start + literal.length }
}

test('formatClock 输出 mm:ss', () => {
  assert.equal(formatClock(0), '00:00')
  assert.equal(formatClock(65), '01:05')
  assert.equal(formatClock(125.8), '02:05')
  assert.equal(formatClock(null), '--:--')
})

test('splitByHits 合并重叠区间并保留未命中文本', () => {
  const parts = splitByHits('abcdef', [
    { start: 1, end: 3 },
    { start: 2, end: 4 }
  ])
  // 重叠区间合并为 [1,4)
  assert.deepEqual(parts, [
    { text: 'a', hit: false },
    { text: 'bcd', hit: true },
    { text: 'ef', hit: false }
  ])
})

test('splitSentences 按句末标点切句', () => {
  const s = splitSentences('One. Two! Three?')
  assert.equal(s.length, 3)
  assert.equal(s[1].text, 'Two!')
})

test('逐句视图：命中归属到正确句子', () => {
  const view = buildSentenceView({ text: TEXT, segments: SEGMENTS, hits: HITS })
  assert.equal(view.timed, true)
  assert.equal(view.sentences.length, 4)

  const s0 = view.sentences[0]
  const s2 = view.sentences[2]
  const s3 = view.sentences[3]

  assert.deepEqual(s0.hitWords, [])
  assert.equal(s0.hasMarks, false)
  assert.deepEqual(s2.hitWords, ['stupid', 'bitch', 'killyou'])
  assert.equal(s2.hasMarks, true)
  assert.equal(s3.hitWords.length, 0)
  assert.equal(s2.start, 6.1)
  assert.equal(view.markedCount, 1)
  assert.equal(view.totalHits, 3)
})

test('逐句视图：句内高亮切片偏移正确（相对句子文本）', () => {
  const view = buildSentenceView({ text: TEXT, segments: SEGMENTS, hits: HITS })
  const s2 = view.sentences[2]
  const hitText = s2.parts.filter((p) => p.hit).map((p) => p.text)
  // 高亮保留原文写法（"kill you"），而标记词名为规范化后的词典词（"killyou"）
  assert.deepEqual(hitText, ['stupid', 'bitch', 'kill you'])
  assert.deepEqual(s2.hitWords, ['stupid', 'bitch', 'killyou'])
  // 切片拼回原文应等于句子文本
  assert.equal(s2.parts.map((p) => p.text).join(''), s2.text)
})

test('逐句视图：AI 判违规原句被标记到对应句子', () => {
  const target = 'you stupid bitch, I will kill you'
  const view = buildSentenceView({ text: TEXT, segments: SEGMENTS, hits: HITS, targetSentence: target })
  assert.equal(view.sentences[2].isTarget, true)
  assert.equal(view.sentences[0].isTarget, false)
})

test('逐句视图：目标句与分段文本不完全一致时按包含关系兜底', () => {
  // AI 返回的原句缺少分段中存在的部分（如断句差异）
  const target = 'I will kill you, do you understand?'
  const view = buildSentenceView({ text: TEXT, segments: SEGMENTS, hits: [], targetSentence: target })
  assert.equal(view.sentences[2].isTarget, true)
})

test('无分段数据时按句切分且无时间轴', () => {
  const view = buildSentenceView({ text: TEXT, segments: [], hits: HITS })
  assert.equal(view.timed, false)
  assert.ok(view.sentences.length >= 4)
  assert.ok(view.sentences.every((s) => s.start == null))
  // 词面兜底：仍能把命中标到含该词的句子
  const marked = view.sentences.filter((s) => s.hasMarks)
  assert.ok(marked.length >= 1)
  assert.ok(marked.some((s) => s.hitWords.includes('stupid')))
})

test('命中落在句间空隙时归属最近的上一句', () => {
  const view = buildSentenceView({
    text: TEXT,
    segments: SEGMENTS,
    hits: [{ word: 'holding', ...span('holding.') }]
  })
  assert.deepEqual(view.sentences[0].hitWords, ['holding'])
})

test('findActiveIndex 依据播放时间定位句子', () => {
  const view = buildSentenceView({ text: TEXT, segments: SEGMENTS, hits: [] })
  assert.equal(findActiveIndex(view.sentences, 0), 0)
  assert.equal(findActiveIndex(view.sentences, 3.5), 1)
  assert.equal(findActiveIndex(view.sentences, 7), 2)
  assert.equal(findActiveIndex(view.sentences, 14), 3)
  // 超出末尾 → 最后一句
  assert.equal(findActiveIndex(view.sentences, 99), 3)
  // 无时间轴 → -1
  const untimed = buildSentenceView({ text: TEXT, segments: [], hits: [] })
  assert.equal(findActiveIndex(untimed.sentences, 5), -1)
})

// ---------- 双声道（双轨）对话视图 ----------

const STEREO_TEXT =
  'Thank you for calling support. I want a refund right now. Let me check your order first. ' +
  'You are a stupid idiot, hurry up!'

const STEREO_SEGMENTS = [
  { start: 0, end: 2.5, text: 'Thank you for calling support.', speaker: '坐席', channel: 'L' },
  { start: 2.6, end: 5.2, text: 'I want a refund right now.', speaker: '客户', channel: 'R' },
  { start: 5.4, end: 8.0, text: 'Let me check your order first.', speaker: '坐席', channel: 'L' },
  { start: 8.2, end: 11.0, text: 'You are a stupid idiot, hurry up!', speaker: '客户', channel: 'R' }
]

test('sideOfChannel 声道映射', () => {
  assert.equal(sideOfChannel('L'), 'left')
  assert.equal(sideOfChannel('r'), 'right')
  assert.equal(sideOfChannel(null), null)
  assert.equal(sideOfChannel('C'), null)
})

test('stripSpeakerTag 去掉 AI 原句里的说话人前缀', () => {
  assert.equal(stripSpeakerTag('【坐席】I will kill you'), 'I will kill you')
  assert.equal(stripSpeakerTag('[客户] You are stupid'), 'You are stupid')
  assert.equal(stripSpeakerTag('没有前缀'), '没有前缀')
})

test('双声道：按声道分成左右两侧并识别说话人', () => {
  const hits = [{ word: 'stupid', ...spanOf(STEREO_TEXT, 'stupid') }]
  const view = buildSentenceView({ text: STEREO_TEXT, segments: STEREO_SEGMENTS, hits })

  assert.equal(view.dialogue, true)
  assert.deepEqual(view.speakers, { left: '坐席', right: '客户' })
  assert.deepEqual(
    view.sentences.map((s) => s.side),
    ['left', 'right', 'left', 'right']
  )
  assert.equal(view.sentences[0].speaker, '坐席')
  assert.equal(view.sentences[1].channel, 'R')
  // 命中落在客户（右侧）那句
  assert.deepEqual(view.sentences[3].hitWords, ['stupid'])
  assert.equal(view.sentences[0].hasMarks, false)
})

test('双声道：AI 原句带说话人前缀时仍能定位到句子', () => {
  const view = buildSentenceView({
    text: STEREO_TEXT,
    segments: STEREO_SEGMENTS,
    hits: [],
    targetSentence: '【客户】You are a stupid idiot, hurry up!'
  })
  assert.equal(view.sentences[3].isTarget, true)
  assert.equal(view.sentences[1].isTarget, false)
})

test('单声道：无声道信息时不进入对话布局', () => {
  const view = buildSentenceView({ text: TEXT, segments: SEGMENTS, hits: HITS })
  assert.equal(view.dialogue, false)
  assert.equal(view.speakers.left, null)
  assert.ok(view.sentences.every((s) => s.side === null))
})
