/**
 * 录音详情工作台共用的状态与派生数据（Recordings.vue / Review.vue）。
 *
 * 两个页面此前各自维护一份「播放源 / 声道切换 / 逐句跳转 / 详情 JSON 字段解析」逻辑
 * （重复 JS 约 115 行、重复样式 562 行），这里统一抽出。
 * 页面差异（i18n 文案、容器布局）通过 labels 注入，不在共享组件里硬编码其它域的 key。
 */
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { formatClock } from '../utils/segments'

/** 解析后端 JSON 字符串字段；非法 / 缺失时返回兜底值 */
export function parseJson(raw, fallback = null) {
  try {
    return JSON.parse(raw || 'null') ?? fallback
  } catch {
    return fallback
  }
}

/** AI 留痕请求体里的 messages（可能缺失 / 非法 / 非对象） */
export function messagesOf(inv) {
  return parseJson(inv?.requestJson, {}).messages || []
}

/** 模型回复 JSON 格式化；解析失败原样展示 */
export function prettyJson(raw) {
  try {
    return JSON.stringify(JSON.parse(raw), null, 2)
  } catch {
    return raw
  }
}

/**
 * @param {import('vue').Ref<object|null>} recordRef 当前录音详情（列表摘要行或详情接口返回体）
 * @param {() => object} labels 当前视图的文案（切换语言时返回新对象，computed 自动重算）
 * @returns 播放器 / 声道 / 转写模式状态与详情派生数据
 */
export function useRecordingDetail(recordRef, labels) {
  const playerRef = ref(null)
  const currentTime = ref(0)
  const follow = ref(true)
  const transcriptMode = ref('segment')
  const audioChannel = ref('mix')
  /** 是否已按「双轨默认对话模式」初始化过（每个工作区实例只自动切一次） */
  const modeInitialized = ref(false)

  const channelFiles = computed(() => parseJson(recordRef.value?.channelFilesJson, null))
  const segments = computed(() => parseJson(recordRef.value?.segmentsJson, []))
  const dfaHits = computed(() => parseJson(recordRef.value?.dfaHitsJson, []))
  const aiResult = computed(() => parseJson(recordRef.value?.aiResultJson, null))
  const dialogueAvailable = computed(() => segments.value.some((s) => s?.channel))
  /** 无 durationSeconds 时用最后一句的结束时间兜底 */
  const totalDuration = computed(() => segments.value[segments.value.length - 1]?.end ?? 0)

  const channelOptions = computed(() => {
    const files = channelFiles.value || {}
    const text = labels()
    const leftName = segments.value.find((s) => s?.channel === 'L')?.speaker || text.channelLeft
    const rightName = segments.value.find((s) => s?.channel === 'R')?.speaker || text.channelRight
    return [
      { key: 'mix', label: text.channelMix, badge: '' },
      { key: 'L', label: files.L ? leftName : text.channelLeft, badge: 'L' },
      { key: 'R', label: files.R ? rightName : text.channelRight, badge: 'R' }
    ]
  })

  /** 播放源：默认混合声道，可切到单独声道；无文件时为空串（展示「无音频」占位） */
  const audioSrc = computed(() => {
    const files = channelFiles.value
    if (files && audioChannel.value !== 'mix' && files[audioChannel.value]) {
      return `/uploads/${files[audioChannel.value]}`
    }
    return recordRef.value?.filePath ? `/uploads/${recordRef.value.filePath}` : ''
  })

  function pause() {
    playerRef.value?.pause?.()
  }

  function switchChannel(key) {
    if (audioChannel.value === key) return
    // 切声道即换 <audio> 的 src：先暂停，避免旧声道继续播放
    pause()
    audioChannel.value = key
    currentTime.value = 0
  }

  function onTimeUpdate(time) {
    currentTime.value = Number(time) || 0
  }

  function seekTo(time) {
    if (time == null) return
    currentTime.value = time
    if (!recordRef.value?.filePath) {
      ElMessage.info(labels().noAudioSeek(formatClock(time)))
      return
    }
    playerRef.value?.seekTo(time)
  }

  // 切换录音（按 id 判定）：重置播放进度 / 声道 / 转写模式
  // 注意按 id 而不是对象引用：复核提交 / 认领会替换当前录音对象，不应重置播放状态
  watch(
    () => recordRef.value?.id,
    () => {
      pause()
      currentTime.value = 0
      audioChannel.value = 'mix'
      transcriptMode.value = 'segment'
      modeInitialized.value = false
    }
  )

  // 详情接口返回后（同一 id 补充 segments/transcript 字段）：双轨录音默认切到双栏对话
  watch(
    [
      () => recordRef.value?.id,
      () => recordRef.value?.segmentsJson,
      () => recordRef.value?.transcript
    ],
    () => {
      const next = recordRef.value
      if (!next || modeInitialized.value) return
      const isDetail = next.segmentsJson !== undefined || next.transcript !== undefined
      if (!isDetail) return
      if (dialogueAvailable.value) transcriptMode.value = 'dialogue'
      else if (transcriptMode.value === 'dialogue') transcriptMode.value = 'segment'
      modeInitialized.value = true
    }
  )

  return {
    playerRef,
    currentTime,
    follow,
    transcriptMode,
    audioChannel,
    channelFiles,
    channelOptions,
    audioSrc,
    segments,
    dfaHits,
    aiResult,
    dialogueAvailable,
    totalDuration,
    pause,
    switchChannel,
    onTimeUpdate,
    seekTo
  }
}
