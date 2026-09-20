/**
 * 逐句转写视图的数据构建（纯函数，便于单测）
 *
 * 输入：全文转写 text、Whisper 分段 segments([{start,end,text}])、DFA 命中 hits([{word,start,end}])、
 *       AI 判定违规原句 targetSentence
 * 输出：每句的 { start, end, text, parts(命中高亮切片), hitWords, isTarget, hasMarks }
 *
 * 关键点：Whisper 分段的文本是全文的子串，先按顺序在全文里定位每句的字符区间，
 * 再把 DFA 命中（偏移基于全文）归属到对应句子；定位失败时回退为词面匹配。
 */

export function formatClock(seconds) {
  if (seconds == null || Number.isNaN(seconds)) return '--:--'
  const total = Math.max(0, Math.floor(seconds))
  const m = Math.floor(total / 60)
  const s = total % 60
  return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`
}

/** 按命中区间切分文本（区间相对该文本），合并重叠区间 */
export function splitByHits(text, ranges) {
  const src = text || ''
  const valid = (ranges || [])
    .filter((r) => r && r.start >= 0 && r.end > r.start && r.start < src.length)
    .map((r) => ({ start: r.start, end: Math.min(r.end, src.length) }))
    .sort((a, b) => a.start - b.start)

  if (!valid.length) return [{ text: src, hit: false }]

  const merged = []
  let cur = null
  for (const r of valid) {
    if (!cur) cur = { ...r }
    else if (r.start <= cur.end) cur.end = Math.max(cur.end, r.end)
    else {
      merged.push(cur)
      cur = { ...r }
    }
  }
  if (cur) merged.push(cur)

  const out = []
  let pos = 0
  for (const r of merged) {
    if (r.start > pos) out.push({ text: src.slice(pos, r.start), hit: false })
    out.push({ text: src.slice(r.start, r.end), hit: true })
    pos = r.end
  }
  if (pos < src.length) out.push({ text: src.slice(pos), hit: false })
  return out
}

function normalizeSegments(segments) {
  return (segments || [])
    .filter((s) => s && typeof s.text === 'string' && s.text.trim())
    .map((s) => ({
      start: typeof s.start === 'number' ? s.start : null,
      end: typeof s.end === 'number' ? s.end : null,
      text: s.text.trim(),
      speaker: typeof s.speaker === 'string' && s.speaker.trim() ? s.speaker.trim() : null,
      channel: typeof s.channel === 'string' && s.channel.trim() ? s.channel.trim().toUpperCase() : null
    }))
}

/** 声道 → 左右栏；无声道信息时返回 null（兼容 L/R、LEFT/RIGHT、1/2 写法） */
export function sideOfChannel(channel) {
  const c = typeof channel === 'string' ? channel.trim().toUpperCase() : ''
  if (c === 'L' || c === 'LEFT' || c === '1') return 'left'
  if (c === 'R' || c === 'RIGHT' || c === '2') return 'right'
  return null
}

/** 去掉 AI 原句里可能带的说话人前缀，如 "【坐席】" / "[客户]" */
export function stripSpeakerTag(text) {
  return (text || '').replace(/^\s*[【\[]\s*[^】\]]{1,12}\s*[】\]]\s*/, '').trim()
}

/** 无分段数据时：按句末标点切句（无时间轴） */
export function splitSentences(text) {
  const src = (text || '').trim()
  if (!src) return []
  const parts = src.match(/[^.!?]+[.!?]*\s*/g) || [src]
  return parts
    .map((p) => p.trim())
    .filter(Boolean)
    .map((t) => ({ start: null, end: null, text: t }))
}

export function buildSentenceView({ text, segments, hits, targetSentence } = {}) {
  const src = text || ''
  const allHits = hits || []

  let list = normalizeSegments(segments)
  const timed = list.length > 0
  if (!timed) list = splitSentences(src)

  // 1) 顺序定位每句在全文中的字符区间
  let cursor = 0
  const sentences = list.map((s, index) => {
    const idx = src.indexOf(s.text, cursor)
    const charStart = idx >= 0 ? idx : null
    if (idx >= 0) cursor = idx + s.text.length
    return {
      index,
      start: s.start,
      end: s.end,
      text: s.text,
      speaker: s.speaker,
      channel: s.channel,
      side: sideOfChannel(s.channel),
      charStart,
      charEnd: charStart == null ? null : charStart + s.text.length,
      hitWords: [],
      hitRanges: [],
      isTarget: false,
      parts: [],
      hasMarks: false
    }
  })
  const ranged = sentences.filter((s) => s.charStart != null)

  // 2) DFA 命中归属到句子（命中偏移基于全文）
  for (const hit of allHits) {
    const word = String(hit?.word ?? '')
    let owner = null
    if (ranged.length) {
      owner = ranged.find((s) => hit.start >= s.charStart && hit.start < s.charEnd) || null
    }
    if (!owner && word) {
      const wl = word.toLowerCase()
      owner = sentences.find((s) => s.text.toLowerCase().includes(wl)) || null
    }
    if (!owner) continue

    if (!owner.hitWords.includes(word)) owner.hitWords.push(word)
    if (owner.charStart != null && hit.start >= owner.charStart && hit.end <= owner.charEnd) {
      owner.hitRanges.push({ start: hit.start - owner.charStart, end: hit.end - owner.charStart })
    } else if (word) {
      const local = owner.text.toLowerCase().indexOf(word.toLowerCase())
      if (local >= 0) owner.hitRanges.push({ start: local, end: local + word.length })
    }
  }

  // 3) AI 判违规原句归属（去掉可能的 【说话人】 前缀后再匹配）
  const target = stripSpeakerTag(targetSentence)
  if (target) {
    const ti = src.indexOf(target)
    let owner = null
    if (ti >= 0) {
      owner = sentences.find((s) => s.charStart != null && ti >= s.charStart && ti < s.charEnd) || null
    }
    if (!owner) {
      const tl = target.toLowerCase()
      owner =
        sentences.find((s) => {
          const st = s.text.toLowerCase()
          return st.includes(tl) || (st.length >= 12 && tl.includes(st))
        }) || null
    }
    if (owner) owner.isTarget = true
  }

  // 4) 生成高亮切片与标记状态
  for (const s of sentences) {
    s.parts = splitByHits(s.text, s.hitRanges)
    s.hasMarks = s.hitWords.length > 0 || s.isTarget
  }

  // 5) 双声道信息：左右说话人名称
  const leftSentence = sentences.find((s) => s.side === 'left' && s.speaker)
  const rightSentence = sentences.find((s) => s.side === 'right' && s.speaker)
  const dialogue = sentences.some((s) => s.side != null)

  return {
    timed,
    dialogue,
    speakers: {
      left: leftSentence?.speaker || null,
      right: rightSentence?.speaker || null
    },
    sentences,
    totalHits: allHits.length,
    markedCount: sentences.filter((s) => s.hasMarks).length
  }
}

/** 当前播放时间对应的句子下标；无匹配返回 -1 */
export function findActiveIndex(sentences, time) {
  if (time == null || Number.isNaN(time) || !sentences?.length) return -1
  for (const s of sentences) {
    if (s.start == null) continue
    const end = s.end != null ? s.end : Number.POSITIVE_INFINITY
    if (time >= s.start && time < end) return s.index
  }
  // 落在句间空隙：归属到最近的前一句
  let fallback = -1
  for (const s of sentences) {
    if (s.start != null && time >= s.start) fallback = s.index
  }
  return fallback
}
