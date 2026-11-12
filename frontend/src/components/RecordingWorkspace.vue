<template>
  <div class="rec-workspace" :class="{ 'is-split': split }">
    <!-- 播放 + 分声道试听 -->
    <div class="player-row">
      <AudioPlayer
        v-if="audioSrc"
        ref="playerRef"
        :src="audioSrc"
        :fallback-duration="record?.durationSeconds || totalDuration"
        @time-update="onTimeUpdate"
      />
      <div v-else class="no-audio tiny dim">{{ labels.noAudio }}</div>
      <div v-if="channelFiles" class="channel-switch">
        <button
          v-for="opt in channelOptions"
          :key="opt.key"
          class="ch-btn"
          :class="{ on: audioChannel === opt.key }"
          @click="switchChannel(opt.key)"
        >
          <span v-if="opt.badge" class="ch-badge sm" :class="opt.key === 'R' ? 'r' : 'l'">{{
            opt.badge
          }}</span>
          {{ opt.label }}
        </button>
      </div>
    </div>

    <!-- 复核页布局：左「转写」/ 右「AI 结论 + 留痕 + 复核表单」 -->
    <div v-if="split" class="work-grid">
      <div class="work-col">
        <div class="block-head">
          <div class="seg-toggle">
            <button
              v-if="dialogueAvailable"
              class="seg-btn"
              :class="{ on: transcriptMode === 'dialogue' }"
              @click="transcriptMode = 'dialogue'"
            >
              <el-icon :size="12"><Connection /></el-icon>
              {{ labels.dialogue }}
            </button>
            <button
              class="seg-btn"
              :class="{ on: transcriptMode === 'segment' }"
              @click="transcriptMode = 'segment'"
            >
              <el-icon :size="12"><Tickets /></el-icon>
              {{ labels.segment }}
            </button>
            <button
              class="seg-btn"
              :class="{ on: transcriptMode === 'full' }"
              @click="transcriptMode = 'full'"
            >
              <el-icon :size="12"><Document /></el-icon>
              {{ labels.full }}
            </button>
          </div>
          <label v-if="transcriptMode !== 'full'" class="follow">
            <el-switch v-model="follow" size="small" />
            <span class="tiny dim">{{ labels.follow }}</span>
          </label>
        </div>

        <div class="transcript-scroll">
          <SegmentTranscript
            v-if="transcriptMode !== 'full'"
            :text="record?.transcript || ''"
            :segments="segments"
            :hits="dfaHits"
            :target-sentence="aiResult?.targetSentence"
            :current-time="currentTime"
            :follow="follow"
            :layout="transcriptMode === 'dialogue' ? 'dialogue' : 'single'"
            @seek="seekTo"
          />
          <TranscriptViewer
            v-else
            :text="record?.transcript || labels.emptyTranscript"
            :hits="dfaHits"
            :target-sentence="aiResult?.targetSentence"
          />
        </div>
      </div>

      <div class="work-col side">
        <div v-if="aiResult" class="verdict" :class="aiResult.violation ? 'bad' : 'ok'">
          <div class="verdict-head">
            <span class="verdict-badge">
              <el-icon :size="14">
                <component :is="aiResult.violation ? WarningFilled : CircleCheckFilled" />
              </el-icon>
              {{ aiResult.violation ? labels.verdictViolation : labels.verdictCompliant }}
            </span>
            <span v-if="aiResult.violationTypeLabel" class="chip warn">{{
              aiResult.violationTypeLabel
            }}</span>
            <span class="conf">
              <span class="tiny dim">{{ labels.confidence }}</span>
              <b class="num">{{
                aiResult.confidence != null ? Math.round(aiResult.confidence * 100) + '%' : '—'
              }}</b>
            </span>
          </div>
          <div class="conf-track">
            <div
              class="conf-fill"
              :class="aiResult.violation ? 'bad' : 'ok'"
              :style="{ width: (aiResult.confidence || 0) * 100 + '%' }"
            ></div>
          </div>
          <div class="verdict-reason">{{ aiResult.reason || labels.reasonFallback }}</div>
          <div v-if="aiResult.screenModel" class="verdict-model tiny">
            <span class="dim">{{ labels.modelLabel }}</span>
            <span class="mono"
              >{{ aiResult.screenProviderLabel || aiResult.screenProvider }} ·
              {{ aiResult.screenModel }}</span
            >
            <span v-if="aiResult.degraded" class="chip warn">{{ labels.degraded }}</span>
          </div>
        </div>

        <template v-if="showTraces">
          <el-collapse class="ai-collapse is-compact">
            <el-collapse-item :name="0">
              <template #title>
                <span class="ai-toggle tiny">{{ labels.tracesCount(aiInvocations.length) }}</span>
              </template>
              <div v-for="inv in aiInvocations" :key="inv.id" class="ai-inv">
                <div class="ai-inv-head tiny">
                  <span class="chip" :class="stageChip(inv.stage)">{{
                    labels.stageLabel(inv.stage)
                  }}</span>
                  <span class="mono">{{ inv.providerLabel }} · {{ inv.model }}</span>
                  <span v-if="!inv.success" class="chip danger">{{ t('common.failed') }}</span>
                  <span class="spacer"></span>
                  <span class="num dim">{{ inv.latencyMs }}ms</span>
                </div>
                <div v-for="(m, i) in messagesOf(inv)" :key="i" class="ai-part">
                  <div class="ai-part-head tiny">
                    <span class="ai-role" :class="m.role">
                      {{ m.role === 'system' ? t('meta.aiRole.SYSTEM') : t('meta.aiRole.USER') }}
                    </span>
                  </div>
                  <pre class="ai-raw">{{ m.content }}</pre>
                </div>
                <div class="ai-part">
                  <div class="ai-part-head tiny">
                    <span class="ai-role reply">{{ t('meta.aiRole.ASSISTANT') }}</span>
                  </div>
                  <pre v-if="inv.responseJson" class="ai-raw reply">{{
                    prettyJson(inv.responseJson)
                  }}</pre>
                  <pre v-else class="ai-raw error">{{ inv.errorMessage || labels.noReply }}</pre>
                </div>
              </div>
            </el-collapse-item>
          </el-collapse>
        </template>

        <slot name="side" />
      </div>
    </div>

    <!-- 录音管理抽屉布局：纵向区块；复核页独有的「人工复检 / 处理轨迹」由父级插槽追加 -->
    <template v-else>
      <div v-if="record?.errorMessage" class="error-banner">
        <el-icon><CircleCloseFilled /></el-icon>
        <div>{{ record.errorMessage }}</div>
      </div>

      <section v-if="aiResult" class="ws-block">
        <div class="ws-block-title">{{ labels.verdictTitle }}</div>
        <div class="verdict" :class="aiResult.violation ? 'bad' : 'ok'">
          <div class="verdict-head">
            <span class="verdict-badge">
              <el-icon :size="14">
                <component :is="aiResult.violation ? WarningFilled : CircleCheckFilled" />
              </el-icon>
              {{ aiResult.violation ? labels.verdictViolation : labels.verdictCompliant }}
            </span>
            <span v-if="aiResult.violationTypeLabel" class="chip warn">{{
              aiResult.violationTypeLabel
            }}</span>
            <span class="conf">
              <span class="tiny dim">{{ labels.confidence }}</span>
              <b class="num">{{
                aiResult.confidence != null ? Math.round(aiResult.confidence * 100) + '%' : '—'
              }}</b>
            </span>
          </div>
          <div class="conf-track">
            <div
              class="conf-fill"
              :class="aiResult.violation ? 'bad' : 'ok'"
              :style="{ width: (aiResult.confidence || 0) * 100 + '%' }"
            ></div>
          </div>
          <div class="verdict-reason">{{ aiResult.reason || labels.reasonFallback }}</div>
          <div v-if="aiResult.screenModel" class="verdict-model tiny">
            <span class="dim">{{ labels.modelLabel }}</span>
            <span class="mono"
              >{{ aiResult.screenProviderLabel || aiResult.screenProvider }} ·
              {{ aiResult.screenModel }}</span
            >
            <span v-if="aiResult.degraded" class="chip warn">{{ labels.degraded }}</span>
          </div>
        </div>
      </section>

      <section class="ws-block">
        <div class="block-head">
          <div class="seg-toggle">
            <button
              v-if="dialogueAvailable"
              class="seg-btn"
              :class="{ on: transcriptMode === 'dialogue' }"
              @click="transcriptMode = 'dialogue'"
            >
              <el-icon :size="12"><Connection /></el-icon>
              {{ labels.dialogue }}
            </button>
            <button
              class="seg-btn"
              :class="{ on: transcriptMode === 'segment' }"
              @click="transcriptMode = 'segment'"
            >
              <el-icon :size="12"><Tickets /></el-icon>
              {{ labels.segment }}
            </button>
            <button
              class="seg-btn"
              :class="{ on: transcriptMode === 'full' }"
              @click="transcriptMode = 'full'"
            >
              <el-icon :size="12"><Document /></el-icon>
              {{ labels.full }}
            </button>
          </div>
          <label v-if="transcriptMode !== 'full'" class="follow">
            <el-switch v-model="follow" size="small" />
            <span class="tiny dim">{{ labels.follow }}</span>
          </label>
        </div>

        <SegmentTranscript
          v-if="transcriptMode !== 'full'"
          :text="record?.transcript || ''"
          :segments="segments"
          :hits="dfaHits"
          :target-sentence="aiResult?.targetSentence"
          :current-time="currentTime"
          :follow="follow"
          :layout="transcriptMode === 'dialogue' ? 'dialogue' : 'single'"
          @seek="seekTo"
        />
        <TranscriptViewer
          v-else
          :text="record?.transcript || labels.emptyTranscript"
          :hits="dfaHits"
          :target-sentence="aiResult?.targetSentence"
        />
      </section>

      <slot />

      <section v-if="showTraces" class="ws-block">
        <div class="block-head">
          <div class="ws-block-title">{{ labels.tracesTitle }}</div>
          <span class="tiny dim">{{ labels.tracesCount(aiInvocations.length) }}</span>
        </div>
        <el-collapse class="ai-collapse">
          <el-collapse-item v-for="inv in aiInvocations" :key="inv.id" :name="inv.id">
            <template #title>
              <div class="ai-inv-title">
                <span class="chip" :class="stageChip(inv.stage)">{{
                  labels.stageLabel(inv.stage)
                }}</span>
                <span class="mono ai-inv-model">{{ inv.providerLabel }} · {{ inv.model }}</span>
                <span v-if="!inv.success" class="chip danger">{{ t('common.failed') }}</span>
                <span v-else-if="inv.degraded" class="chip warn">{{
                  t('meta.aiStatus.DEGRADED')
                }}</span>
                <span v-else class="chip ok">{{ t('common.success') }}</span>
                <span class="spacer"></span>
                <span class="num tiny dim">{{ inv.latencyMs }}ms</span>
              </div>
            </template>

            <div class="ai-meta tiny dim">
              <div>
                {{ labels.endpoint }}<span class="mono">{{ inv.endpoint || '—' }}</span>
              </div>
              <div>
                <span class="num">{{ formatTime(inv.createdAt) }}</span>
                · {{ labels.requestChars(inv.requestChars) }} ·
                {{ labels.responseChars(inv.responseChars) }}
              </div>
            </div>

            <div v-for="(m, i) in messagesOf(inv)" :key="i" class="ai-part">
              <div class="ai-part-head">
                <span class="ai-role" :class="m.role">
                  {{ m.role === 'system' ? t('meta.aiRole.SYSTEM') : t('meta.aiRole.USER') }}
                </span>
                <span class="tiny dim">{{ labels.charCount((m.content || '').length) }}</span>
              </div>
              <pre class="ai-raw">{{ m.content }}</pre>
            </div>

            <div v-if="!inv.requestJson" class="tiny dim">{{ labels.noRequestBody }}</div>

            <div class="ai-part">
              <div class="ai-part-head">
                <span class="ai-role reply">{{ t('meta.aiRole.ASSISTANT') }}</span>
                <span class="tiny dim">{{ labels.charCount(inv.responseChars) }}</span>
              </div>
              <pre v-if="inv.responseJson" class="ai-raw reply">{{
                prettyJson(inv.responseJson)
              }}</pre>
              <pre v-else class="ai-raw error">{{ inv.errorMessage || labels.noReply }}</pre>
            </div>
          </el-collapse-item>
        </el-collapse>
      </section>
    </template>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { formatTime } from '../constants'
import { useI18n } from '../i18n'
import AudioPlayer from './AudioPlayer.vue'
import SegmentTranscript from './SegmentTranscript.vue'
import TranscriptViewer from './TranscriptViewer.vue'
import { messagesOf, prettyJson, useRecordingDetail } from '../composables/useRecordingDetail'
import {
  CircleCheckFilled,
  CircleCloseFilled,
  Connection,
  Document,
  Tickets,
  WarningFilled
} from './icons.js'

/**
 * 录音详情工作台（Recordings.vue / Review.vue 共用）。
 *
 * props.labels 由各视图按自己域名的 i18n key 组装，避免跨域复用文案 key：
 * - 播放/声道：channelMix/channelLeft/channelRight/noAudio/noAudioSeek(clock)
 * - 转写：dialogue/segment/full/follow/emptyTranscript
 * - AI 结论：verdictTitle(纵向布局用)/verdictViolation/verdictCompliant/confidence/modelLabel/degraded/reasonFallback
 * - AI 留痕：tracesTitle(有则用详细分区，无则用紧凑折叠)/tracesCount(count)/stageLabel(stage)/
 *            endpoint/requestChars(n)/responseChars(n)/charCount(n)/noRequestBody/noReply
 */
const props = defineProps({
  record: { type: Object, default: null },
  aiInvocations: { type: Array, default: () => [] },
  /** 是否渲染 AI 留痕（ADMIN 专属接口，父级已按角色 gate 请求） */
  admin: { type: Boolean, default: false },
  /** false：录音抽屉纵向布局；true：复核页「左转写 / 右结论 + 复核表单」布局 */
  split: { type: Boolean, default: false },
  labels: { type: Object, required: true }
})

const { t } = useI18n()

const recordRef = computed(() => props.record)
const {
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
} = useRecordingDetail(recordRef, () => props.labels)

const showTraces = computed(() => props.admin && props.aiInvocations.length > 0)

/** 留痕阶段芯片配色：词挖掘 / 检索 embedding 用中性色，其余按复筛展示 */
const stageChip = (stage) => (stage === 'MINING' || stage === 'EMBED_QUERY' ? 'info' : 'brand')

defineExpose({ pause, seekTo })
</script>

<style scoped>
.rec-workspace {
  display: flex;
  flex-direction: column;
  gap: 16px;
  min-width: 0;
}
/* 复核页：两栏布局自身用 margin 控制与播放器的间距，避免 gap + margin 双重叠加 */
.rec-workspace.is-split {
  gap: 0;
}

/* ---------- 播放 + 声道 ---------- */
.player-row {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
.is-split .player-row {
  margin-top: 16px;
}
.player-row .audio-player {
  flex: 1 1 300px;
  min-width: 0;
}
.no-audio {
  flex: 1;
  height: 40px;
  display: flex;
  align-items: center;
  padding: 0 14px;
  border: 1px dashed var(--border);
  border-radius: 12px;
  background: var(--ink-50);
}
.channel-switch {
  flex: none;
  display: inline-flex;
  background: var(--ink-100);
  border-radius: 9px;
  padding: 3px;
  gap: 3px;
}
.ch-btn {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  height: 26px;
  padding: 0 10px;
  border: none;
  border-radius: 7px;
  background: transparent;
  color: var(--el-text-color-regular);
  font-size: 12px;
  font-family: inherit;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.16s ease;
}
.ch-btn.on {
  background: #fff;
  color: var(--brand-600);
  box-shadow: var(--sh-xs);
}
.ch-badge.sm {
  width: 16px;
  height: 16px;
  border-radius: 5px;
  display: grid;
  place-items: center;
  font-size: 10px;
  font-weight: 700;
  font-family: var(--font-mono);
  color: #fff;
}
.ch-badge.sm.l {
  background: var(--brand-500);
}
.ch-badge.sm.r {
  background: #64748b;
}

/* ---------- 区块骨架 ---------- */
.ws-block {
  border: 1px solid var(--border);
  border-radius: var(--r-lg);
  padding: 14px 16px;
  background: #fff;
  box-shadow: var(--sh-xs);
  min-width: 0;
}
.ws-block-title {
  font-size: 13.5px;
  font-weight: 600;
  color: var(--ink-800);
  margin-bottom: 12px;
}
.block-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 12px;
}
.error-banner {
  display: flex;
  gap: 9px;
  align-items: flex-start;
  padding: 11px 13px;
  border-radius: var(--r-md);
  background: var(--danger-soft);
  border: 1px solid #f7c9c9;
  color: #b3261e;
  font-size: 12.5px;
  line-height: 1.6;
}

/* ---------- 转写模式切换 ---------- */
.seg-toggle {
  display: inline-flex;
  background: var(--ink-100);
  border-radius: 9px;
  padding: 3px;
  gap: 3px;
}
.seg-btn {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  height: 26px;
  padding: 0 11px;
  border: none;
  border-radius: 7px;
  background: transparent;
  color: var(--el-text-color-regular);
  font-size: 12.5px;
  font-family: inherit;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.16s ease;
}
.seg-btn.on {
  background: #fff;
  color: var(--brand-600);
  box-shadow: var(--sh-xs);
}
.follow {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  cursor: pointer;
}

/* ---------- 复核页两栏布局 ---------- */
.work-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.25fr) minmax(320px, 0.95fr);
  gap: 18px;
  margin-top: 16px;
}
/* 详情列窄于 ~740px 时改为上下排列，避免两栏都过窄 */
@media (max-width: 1380px) {
  .work-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
.work-col {
  min-width: 0;
}
.work-col.side {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.transcript-scroll {
  max-height: 470px;
  overflow-y: auto;
  padding-right: 4px;
}

/* ---------- AI 结论 ---------- */
.verdict {
  border-radius: var(--r-md);
  padding: 14px 16px;
  border: 1px solid var(--border);
  background: #fbfcfe;
  box-shadow: var(--sh-xs);
}
.verdict.bad {
  background: linear-gradient(180deg, #fff6f6, #fffdfd);
  border-color: #f7d0d0;
  box-shadow:
    inset 3px 0 0 #ef4444,
    var(--sh-xs);
}
.verdict.ok {
  background: linear-gradient(180deg, #f5fbf7, #fdfefd);
  border-color: #cfe9d8;
  box-shadow:
    inset 3px 0 0 var(--ok),
    var(--sh-xs);
}
.verdict-head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.verdict-badge {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 13px;
  font-weight: 600;
}
.verdict.bad .verdict-badge {
  color: #c92c2c;
}
.verdict.ok .verdict-badge {
  color: var(--ok-ink);
}
.conf {
  display: inline-flex;
  align-items: baseline;
  gap: 4px;
  margin-left: auto;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.conf b {
  font-size: 13px;
  color: var(--ink-800);
}
.conf-track {
  height: 7px;
  border-radius: 999px;
  background: rgba(16, 26, 48, 0.07);
  box-shadow: inset 0 0 0 1px rgba(16, 26, 48, 0.05);
  margin: 11px 0 10px;
  overflow: hidden;
}
.conf-fill {
  height: 100%;
  border-radius: 999px;
  transition: width 0.5s ease;
}
.conf-fill.bad {
  background: linear-gradient(90deg, #f87171, #ef4444);
  box-shadow: 0 1px 3px rgba(239, 68, 68, 0.35);
}
.conf-fill.ok {
  background: linear-gradient(90deg, #4ade80, #16a34a);
  box-shadow: 0 1px 3px rgba(22, 163, 74, 0.3);
}
.verdict-reason {
  font-size: 12.5px;
  line-height: 1.7;
  color: var(--el-text-color-regular);
}
.verdict-model {
  display: flex;
  align-items: center;
  gap: 7px;
  flex-wrap: wrap;
  margin-top: 7px;
  padding-top: 7px;
  border-top: 1px dashed var(--border);
}
.verdict-model .mono {
  color: var(--ink-600);
}

/* ---------- AI 留痕 ---------- */
.ai-collapse {
  border-top: none;
}
.ai-collapse.is-compact {
  margin-top: 0;
}
.ai-collapse :deep(.el-collapse-item__header) {
  height: auto;
  min-height: 34px;
  padding: 4px 0;
  line-height: 1.5;
}
.ai-toggle {
  color: var(--brand-600);
  font-weight: 600;
}
.ai-inv-title {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding-right: 10px;
  min-width: 0;
}
.ai-inv-model {
  font-size: 12px;
  color: var(--ink-600);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.ai-inv + .ai-inv {
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px dashed var(--border);
}
.ai-inv-head {
  display: flex;
  align-items: center;
  gap: 8px;
  row-gap: 4px;
  flex-wrap: wrap;
  color: var(--ink-600);
}
.ai-meta {
  line-height: 1.9;
  margin-bottom: 4px;
}
.ai-part {
  margin-top: 11px;
}
.ai-part-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 5px;
}
.ai-role {
  font-size: 12px;
  font-weight: 600;
  color: var(--ink-600);
}
.ai-role.system {
  color: var(--brand-600);
}
.ai-role.reply {
  color: var(--ok);
}
.ai-raw {
  margin: 0;
  padding: 10px 12px;
  max-height: 260px;
  overflow: auto;
  background: var(--ink-50);
  border: 1px solid var(--border);
  border-radius: var(--r-sm);
  font-family: var(--font-mono);
  font-size: 11.5px;
  line-height: 1.68;
  color: var(--ink-600);
  white-space: pre-wrap;
  word-break: break-word;
}
.ai-raw.reply {
  background: var(--ok-soft);
  border-color: #cfe9d9;
}
.ai-raw.error {
  background: var(--danger-soft);
  border-color: #f3cccc;
  color: #a32424;
}
</style>
