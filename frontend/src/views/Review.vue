<template>
  <div class="page stack">
    <!-- 顶部：队列概览 + 视图切换 + 队列筛选（两行：操作行 / 说明行） -->
    <section class="panel topbar">
      <div class="topbar-main">
        <div class="seg">
          <button class="seg-item" :class="{ on: tab === 'pending' }" @click="switchTab('pending')">
            {{ t('review.tabs.pending') }}
            <span class="seg-count" :class="{ hot: pendingTotal > 0 }">{{ pendingTotal }}</span>
          </button>
          <button class="seg-item" :class="{ on: tab === 'history' }" @click="switchTab('history')">
            {{ t('review.tabs.history') }}
            <span class="seg-count">{{ historyTotal }}</span>
          </button>
        </div>
        <div v-if="tab === 'pending'" class="queue-filters">
          <el-select
            v-model="priorityFilter"
            :placeholder="t('review.filters.allPriorities')"
            clearable
            class="prio-select"
            @change="applyQueueFilters"
          >
            <el-option
              v-for="opt in PRIORITY_OPTIONS"
              :key="opt.value"
              :label="t('review.filters.priorityOption', { label: opt.label })"
              :value="opt.value"
            />
          </el-select>
          <div class="assign-filter">
            <button
              v-for="opt in ASSIGN_FILTERS"
              :key="opt.key"
              class="af-btn"
              :class="{ on: assignFilter === opt.key }"
              @click="setAssignFilter(opt.key)"
            >{{ opt.label }}</button>
          </div>
        </div>
      </div>
      <div v-if="tab === 'pending'" class="topbar-hint">
        <el-icon :size="13"><InfoFilled /></el-icon>
        <span class="queue-logic">{{ t('review.queue.hint') }}</span>
      </div>
    </section>

    <!-- 待复检工作区 -->
    <div v-show="tab === 'pending'" class="workspace">
      <!-- 队列 -->
      <aside class="panel queue">
        <div class="queue-head">
          <div class="section-title">{{ t('review.queue.title') }}</div>
          <el-input
            v-model="queueKeyword"
            :placeholder="t('review.queue.searchPlaceholder')"
            clearable
            class="queue-search"
            :title="t('review.queue.searchTitle')"
            @keyup.enter="applyQueueFilters"
            @clear="applyQueueFilters"
          >
            <template #prefix><el-icon><Search /></el-icon></template>
          </el-input>
        </div>
        <div class="queue-list">
          <button
            v-for="(r, i) in pendingRows"
            :key="r.id"
            class="queue-item"
            :class="{ on: current?.id === r.id }"
            :title="queueTooltip(r)"
            @click="select(r)"
          >
            <span class="queue-index num">{{ queueIndex(i) }}</span>
            <span class="queue-body">
              <span class="queue-name ellipsis">{{ r.fileName }}</span>
              <span class="queue-meta">
                <span class="dot" :style="{ background: priorityDot(r.priority) }"></span>
                <span class="queue-type">{{ r.aiViolationTypeLabel || t('review.queue.uncategorized') }}</span>
                <span
                  v-if="isSampled(r)"
                  class="queue-sampled"
                  :title="t('review.queue.sampledTitle')"
                >{{ t('review.badges.sampled') }}</span>
                <span class="queue-prio" :class="priorityClass(r.priority)">{{ priorityMeta(r.priority).label }}</span>
                <span
                  v-if="r.assignedTo"
                  class="queue-assignee"
                  :class="{ me: r.assignedTo === auth.username }"
                >{{ r.assignedTo === auth.username ? t('review.queue.mine') : r.assignedTo }}</span>
                <span v-else class="queue-assignee unassigned">{{ t('review.assign.unassigned') }}</span>
                <span class="queue-time num">{{ formatRelative(r.uploadTime) }}</span>
              </span>
            </span>
          </button>

          <div v-if="!pendingRows.length" class="empty-block">
            <el-icon :size="32" class="empty-icon"><CircleCheckFilled /></el-icon>
            <div class="empty-title">
              {{ hasQueueFilter ? t('review.queue.emptyFilteredTitle') : t('review.queue.emptyTitle') }}
            </div>
            <div class="tiny dim">
              {{ hasQueueFilter ? t('review.queue.emptyFilteredHint') : t('review.queue.emptyHint') }}
            </div>
          </div>
        </div>

        <!-- 队列分页：筛选与分页都由服务端完成，翻页结果与「共 N 条」一致 -->
        <div v-if="pendingTotal" class="queue-foot">
          <span class="queue-total num">{{ t('review.queue.total', { total: pendingTotal }) }}</span>
          <el-pagination
            size="small"
            layout="prev, pager, next"
            :pager-count="5"
            :total="pendingTotal"
            :page-size="pendingPageSize"
            :current-page="pendingPage + 1"
            :disabled="pendingLoading"
            @current-change="changePendingPage"
          />
        </div>
      </aside>

      <!-- 工作区 -->
      <section class="panel work" v-loading="detailLoading">
        <template v-if="current">
          <!-- 头部 -->
          <div class="work-head">
            <div class="min-w-0">
              <div class="work-name ellipsis">{{ current.fileName }}</div>
              <div class="work-chips">
                <span class="chip mono">#{{ current.id }}</span>
                <span class="chip">{{ formatDuration(current.durationSeconds) }}</span>
                <span class="chip danger">{{ t('review.work.dfaHits', { count: current.hitCount }) }}</span>
                <span v-if="aiResult?.violationTypeLabel" class="chip warn">
                  {{ t('review.work.aiType', { type: aiResult.violationTypeLabel }) }}
                </span>
                <span
                  v-if="isSampled(current)"
                  class="chip warn"
                  :title="t('review.work.sampledTitle')"
                >{{ t('review.badges.sampled') }}</span>
                <PriorityTag :priority="current.priority" />
                <span class="chip" :class="{ brand: current.assignedTo === auth.username }">
                  {{ current.assignedTo
                    ? (current.assignedTo === auth.username
                      ? t('review.assign.assignedToMe')
                      : t('review.assign.assignedTo', { name: current.assignedTo }))
                    : t('review.assign.unassigned') }}
                </span>
                <span v-if="progress.required > 1" class="chip info">
                  {{ t('review.work.reviewProgress', { progress: progress.text }) }}
                </span>
                <span class="chip num">{{ formatTime(current.uploadTime) }}</span>
              </div>
            </div>
            <div class="work-actions">
              <el-button
                v-if="canClaim"
                type="primary"
                :icon="Flag"
                :loading="claiming"
                @click="claimCurrent"
              >{{ t('review.actions.claim') }}</el-button>
              <el-button v-if="canAssign" :icon="User" @click="assignVisible = true">
                {{ t('review.actions.assign') }}
              </el-button>
              <el-button :icon="Right" @click="autoNext">
                {{ t('review.actions.next') }}<template v-if="remaining > 0">{{
                  t('review.actions.nextRemaining', { count: remaining })
                }}</template>
              </el-button>
            </div>
          </div>

          <!-- 已出结论 -->
          <div v-if="alreadyReviewed" class="reviewed">
            <el-icon :size="38" :class="current.reviewResult === 'CONFIRMED_VIOLATION' ? 'icon-bad' : 'icon-ok'">
              <component :is="current.reviewResult === 'CONFIRMED_VIOLATION' ? 'WarningFilled' : 'CircleCheckFilled'" />
            </el-icon>
            <div class="reviewed-title">
              {{
                current.reviewResult === 'CONFIRMED_VIOLATION'
                  ? t('review.reviewed.confirmed')
                  : t('review.reviewed.falsePositive')
              }}
            </div>
            <div class="reviewed-sub">
              {{
                current.reviewResult === 'CONFIRMED_VIOLATION'
                  ? t('review.reviewed.confirmedSub')
                  : t('review.reviewed.falsePositiveSub')
              }}
            </div>
            <el-button type="primary" @click="autoNext">{{ t('review.actions.processNext') }}</el-button>
          </div>

          <!-- 已提交复核，等待下一位复核人（双人复核进度未满） -->
          <div v-else-if="submittedPending" class="reviewed submitted">
            <el-icon :size="38" class="icon-warn"><Clock /></el-icon>
            <div class="reviewed-title">
              {{ t('review.submitted.title', { progress: submittedPending.text }) }}
            </div>
            <div class="reviewed-sub">
              {{
                t('review.submitted.hint', {
                  required: submittedPending.required,
                  count: submittedPending.count
                })
              }}
            </div>
            <el-button type="primary" @click="autoNext">{{ t('review.actions.processNext') }}</el-button>
          </div>

          <template v-else>
            <div class="player-row">
              <AudioPlayer
                v-if="current.filePath"
                ref="playerRef"
                :src="audioSrc"
                :fallback-duration="current.durationSeconds || totalDuration"
                @time-update="onTimeUpdate"
              />
              <div v-else class="no-audio tiny dim">{{ t('review.player.noAudio') }}</div>

              <!-- 分声道试听：双轨录音可单独听坐席 / 客户 -->
              <div v-if="channelFiles" class="channel-switch">
                <button
                  v-for="opt in channelOptions"
                  :key="opt.key"
                  class="ch-btn"
                  :class="{ on: audioChannel === opt.key }"
                  @click="switchChannel(opt.key)"
                >
                  <span v-if="opt.badge" class="ch-badge sm" :class="opt.key === 'R' ? 'r' : 'l'">{{ opt.badge }}</span>
                  {{ opt.label }}
                </button>
              </div>
            </div>

            <div class="work-grid">
              <!-- 左：转写（双栏对话 / 逐句 / 全文） -->
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
                      {{ t('review.transcript.dialogue') }}
                    </button>
                    <button
                      class="seg-btn"
                      :class="{ on: transcriptMode === 'segment' }"
                      @click="transcriptMode = 'segment'"
                    >
                      <el-icon :size="12"><Tickets /></el-icon>
                      {{ t('review.transcript.segment') }}
                    </button>
                    <button
                      class="seg-btn"
                      :class="{ on: transcriptMode === 'full' }"
                      @click="transcriptMode = 'full'"
                    >
                      <el-icon :size="12"><Document /></el-icon>
                      {{ t('review.transcript.full') }}
                    </button>
                  </div>
                  <label v-if="transcriptMode !== 'full'" class="follow">
                    <el-switch v-model="follow" size="small" />
                    <span class="tiny dim">{{ t('review.transcript.follow') }}</span>
                  </label>
                </div>

                <div class="transcript-scroll">
                  <SegmentTranscript
                    v-if="transcriptMode !== 'full'"
                    :text="current.transcript || ''"
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
                    :text="current.transcript || t('review.transcript.empty')"
                    :hits="dfaHits"
                    :target-sentence="aiResult?.targetSentence"
                  />
                </div>
              </div>

              <!-- 右：AI 判断 + 结论 -->
              <div class="work-col side">
                <div class="verdict" :class="aiResult?.violation ? 'bad' : 'ok'">
                  <div class="verdict-head">
                    <span class="verdict-badge">
                      <el-icon :size="14">
                        <component :is="aiResult?.violation ? 'WarningFilled' : 'CircleCheckFilled'" />
                      </el-icon>
                      {{
                        t('review.ai.verdict', {
                          result: aiResult?.violation
                            ? t('review.ai.judgedViolation')
                            : t('review.ai.judgedNormal')
                        })
                      }}
                    </span>
                    <span v-if="aiResult?.violationTypeLabel" class="chip warn">{{ aiResult.violationTypeLabel }}</span>
                    <span class="conf">
                      <span class="tiny dim">{{ t('review.ai.confidence') }}</span>
                      <b class="num">{{ aiResult?.confidence != null ? Math.round(aiResult.confidence * 100) + '%' : '—' }}</b>
                    </span>
                  </div>
                  <div class="conf-track">
                    <div
                      class="conf-fill"
                      :class="aiResult?.violation ? 'bad' : 'ok'"
                      :style="{ width: (aiResult?.confidence || 0) * 100 + '%' }"
                    ></div>
                  </div>
                  <div class="verdict-reason">{{ aiResult?.reason || t('review.ai.noReason') }}</div>
                  <div v-if="aiResult?.screenModel" class="verdict-model tiny">
                    <span class="dim">{{ t('review.ai.model') }}</span>
                    <span class="mono">
                      {{ aiResult.screenProviderLabel || aiResult.screenProvider }} · {{ aiResult.screenModel }}
                    </span>
                    <span v-if="aiResult.degraded" class="chip warn">{{ t('review.ai.degraded') }}</span>
                  </div>

                  <!-- AI 调用留痕：点开看完整提示词与模型原始回复 -->
                  <el-collapse v-if="aiInvocations.length" class="ai-collapse">
                    <el-collapse-item :name="0">
                      <template #title>
                        <span class="ai-toggle tiny">
                          {{ t('review.ai.invocations', { count: aiInvocations.length }) }}
                        </span>
                      </template>
                      <div v-for="inv in aiInvocations" :key="inv.id" class="ai-inv">
                        <div class="ai-inv-head tiny">
                          <span class="chip" :class="inv.stage === 'MINING' ? 'info' : 'brand'">
                            {{ inv.stage === 'MINING' ? t('meta.aiStage.MINING') : t('meta.aiStage.SCREEN') }}
                          </span>
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
                          <pre v-if="inv.responseJson" class="ai-raw reply">{{ prettyJson(inv.responseJson) }}</pre>
                          <pre v-else class="ai-raw error">{{ inv.errorMessage || t('review.ai.noResponse') }}</pre>
                        </div>
                      </div>
                    </el-collapse-item>
                  </el-collapse>
                </div>

                <!-- 复核工作流：进度 / 权限提示 / 复核历史 -->
                <div class="workflow">
                  <div class="block-head">
                    <div class="block-title no-mb">{{ t('review.progress.title') }}</div>
                    <span class="tiny dim">
                      {{
                        progress.required > 1
                          ? t('review.progress.multi', {
                            required: progress.required,
                            count: progress.count
                          })
                          : t('review.progress.single')
                      }}
                    </span>
                  </div>
                  <div class="progress-row">
                    <span
                      v-for="i in progress.required"
                      :key="i"
                      class="progress-dot"
                      :class="{ on: i <= progress.count }"
                    ></span>
                    <b class="num">{{ progress.text }}</b>
                    <span class="tiny dim">{{ t('review.progress.unit') }}</span>
                  </div>

                  <el-alert
                    v-if="readOnly"
                    type="info"
                    :closable="false"
                    show-icon
                    :title="t('review.alerts.readOnly')"
                    class="wf-alert"
                  />
                  <el-alert
                    v-else-if="sameReviewerBlocked"
                    type="warning"
                    :closable="false"
                    show-icon
                    :title="t('review.alerts.sameReviewer')"
                    class="wf-alert"
                  />
                  <el-alert
                    v-else-if="progress.required > 1 && progress.count > 0"
                    type="info"
                    :closable="false"
                    show-icon
                    :title="t('review.alerts.pendingNext', { count: progress.count, required: progress.required })"
                    class="wf-alert"
                  />

                  <div class="history-head">
                    <div class="block-title no-mb">{{ t('review.history.title') }}</div>
                    <span class="tiny dim">{{
                      reviewHistory.length
                        ? tn('common.recordsUnit', reviewHistory.length)
                        : t('review.history.none')
                    }}</span>
                  </div>
                  <div v-if="reviewHistoryLoading" class="tiny dim">{{ t('common.loading') }}</div>
                  <div v-else-if="!reviewHistory.length" class="tiny dim">{{ t('review.history.empty') }}</div>
                  <el-timeline v-else class="review-timeline">
                    <el-timeline-item
                      v-for="(h, i) in reviewHistory"
                      :key="h.id != null ? h.id : i"
                      :timestamp="formatTime(h.createdAt)"
                      placement="top"
                      :type="h.result === 'CONFIRMED_VIOLATION' ? 'danger' : 'success'"
                    >
                      <div class="rh-line">
                        <span class="strong">{{ h.reviewerUsername || '—' }}</span>
                        <span class="chip" :class="h.result === 'CONFIRMED_VIOLATION' ? 'danger' : 'ok'">
                          {{ h.result === 'CONFIRMED_VIOLATION'
                            ? t('review.result.confirmed')
                            : t('review.result.normal') }}
                        </span>
                        <span v-if="h.violationType" class="chip warn">
                          {{ VIOLATION_TYPES[h.violationType] || h.violationType }}
                        </span>
                        <span v-if="h.round != null" class="chip info">
                          {{ t('review.history.round', { round: h.round }) }}
                        </span>
                      </div>
                      <div v-if="h.comment" class="rh-comment">{{ h.comment }}</div>
                    </el-timeline-item>
                  </el-timeline>
                </div>

                <div v-if="readOnly" class="readonly-note">
                  <el-icon :size="15"><View /></el-icon>
                  {{ t('review.readOnly.note') }}
                </div>
                <div v-else class="form">
                  <div class="block-title">{{ t('review.form.title') }}</div>
                  <div class="verdict-choice">
                    <button
                      class="choice"
                      :class="{ on: form.result === 'CONFIRMED_VIOLATION', bad: form.result === 'CONFIRMED_VIOLATION' }"
                      @click="form.result = 'CONFIRMED_VIOLATION'"
                    >
                      <el-icon :size="15"><WarningFilled /></el-icon>
                      {{ t('review.form.confirmViolation') }}
                    </button>
                    <button
                      class="choice"
                      :class="{ on: form.result === 'FALSE_POSITIVE', ok: form.result === 'FALSE_POSITIVE' }"
                      @click="form.result = 'FALSE_POSITIVE'"
                    >
                      <el-icon :size="15"><CircleCheckFilled /></el-icon>
                      {{ t('review.form.markNormal') }}
                    </button>
                  </div>

                  <div v-if="form.result === 'CONFIRMED_VIOLATION'" class="types">
                    <div class="types-label">{{ t('review.form.violationType') }}</div>
                    <div class="types-grid">
                      <button
                        v-for="(label, code) in VIOLATION_TYPES"
                        :key="code"
                        class="type-chip"
                        :class="{ on: form.violationType === code }"
                        @click="form.violationType = code"
                      >
                        {{ label }}
                      </button>
                    </div>
                  </div>

                  <el-input
                    v-model="form.comment"
                    type="textarea"
                    :rows="3"
                    maxlength="300"
                    show-word-limit
                    :placeholder="t('review.form.commentPlaceholder')"
                  />

                  <div v-if="sameReviewerBlocked" class="form-warn">
                    <el-icon :size="13"><WarningFilled /></el-icon>
                    {{ t('review.form.sameReviewerWarn') }}
                  </div>

                  <el-button
                    type="primary"
                    class="submit"
                    :loading="submitting"
                    :disabled="sameReviewerBlocked"
                    @click="submit"
                  >
                    {{
                      form.result === 'CONFIRMED_VIOLATION'
                        ? t('review.form.submitConfirmed')
                        : t('review.form.submitNormal')
                    }}
                  </el-button>
                </div>
              </div>
            </div>
          </template>
        </template>

        <div v-else class="empty-block">
          <el-icon :size="34" class="empty-icon"><DocumentChecked /></el-icon>
          <div class="empty-title">{{ t('review.empty.selectTitle') }}</div>
          <div class="tiny dim">{{ t('review.empty.selectHint') }}</div>
        </div>
      </section>
    </div>

    <!-- 已复检 -->
    <section v-show="tab === 'history'" class="panel">
      <el-table :data="historyRows" v-loading="historyLoading" scrollbar-always-on>
        <el-table-column :label="t('review.table.recording')" min-width="240">
          <template #default="{ row }">
            <div class="file-cell">
              <div class="file-icon"><el-icon><Microphone /></el-icon></div>
              <div class="min-w-0">
                <div class="ellipsis strong">{{ row.fileName }}</div>
                <div class="tiny dim mono">#{{ row.id }}</div>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column :label="t('review.table.result')" width="118" align="center">
          <template #default="{ row }">
            <span class="chip" :class="row.reviewResult === 'CONFIRMED_VIOLATION' ? 'danger' : 'ok'">
              {{ row.reviewResult === 'CONFIRMED_VIOLATION'
                ? t('review.result.confirmed')
                : t('review.result.falsePositive') }}
            </span>
          </template>
        </el-table-column>
        <el-table-column :label="t('common.type')" width="112" align="center">
          <template #default="{ row }">
            <span v-if="row.violationTypeLabel" class="chip warn">{{ row.violationTypeLabel }}</span>
            <span v-else class="dim">—</span>
          </template>
        </el-table-column>
        <el-table-column
          prop="reviewComment"
          :label="t('review.table.comment')"
          min-width="200"
          show-overflow-tooltip
        >
          <template #default="{ row }">
            <span :class="{ dim: !row.reviewComment }">{{ row.reviewComment || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="reviewer" :label="t('review.table.reviewer')" width="100" align="center" />
        <el-table-column :label="t('review.table.reviewedAt')" width="160">
          <template #default="{ row }">
            <span class="num dim">{{ formatTime(row.reviewTime) }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('common.actions')" width="88" align="right">
          <template #default="{ row }">
            <el-button size="small" link type="primary" @click="openHistoryDetail(row)">
              {{ t('common.detail') }}
            </el-button>
          </template>
        </el-table-column>
        <template #empty>
          <div class="empty-block">
            <div class="empty-title">{{ t('review.table.emptyTitle') }}</div>
            <div class="tiny dim">{{ t('review.table.emptyHint') }}</div>
          </div>
        </template>
      </el-table>
      <div class="pager">
        <el-pagination
          layout="total, prev, pager, next"
          :total="historyTotal"
          :page-size="historyPageSize"
          :current-page="historyPage + 1"
          @current-change="(p) => { historyPage = p - 1; loadHistory() }"
        />
      </div>
    </section>

    <!-- 历史详情 -->
    <el-drawer v-model="historyVisible" size="700px" :with-header="false" append-to-body>
      <template v-if="historyDetail">
        <div class="work-head">
          <div class="min-w-0">
            <div class="work-name ellipsis">{{ historyDetail.fileName }}</div>
            <div class="work-chips">
              <span class="chip" :class="historyDetail.reviewResult === 'CONFIRMED_VIOLATION' ? 'danger' : 'ok'">
                {{ historyDetail.reviewResult === 'CONFIRMED_VIOLATION'
                  ? t('review.result.confirmed')
                  : t('review.result.falsePositive') }}
              </span>
              <span v-if="historyDetail.violationTypeLabel" class="chip warn">{{ historyDetail.violationTypeLabel }}</span>
              <span v-if="isSampled(historyDetail)" class="chip warn">{{ t('review.badges.sampled') }}</span>
              <span class="chip">{{ historyDetail.reviewer || '—' }} · {{ formatTime(historyDetail.reviewTime) }}</span>
            </div>
          </div>
          <el-button
            :icon="Close"
            circle
            size="small"
            @click="historyVisible = false"
            :aria-label="t('common.close')"
            :title="t('common.close')"
          />
        </div>
        <div v-if="historyDetail.reviewComment" class="comment-block">
          {{ historyDetail.reviewComment }}
        </div>
        <div class="block-title mt-14">{{ t('review.history.transcript') }}</div>
        <TranscriptViewer :text="historyDetail.transcript" :hits="historyHits" />
      </template>
    </el-drawer>

    <!-- 指派复核人（ADMIN） -->
    <AssignDialog v-model="assignVisible" :recording="current" @assigned="onAssigned" />
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useI18n } from '../i18n'
import { ElMessage } from 'element-plus'
import { Search, Right, Close, Flag, User } from '@element-plus/icons-vue'
import api from '../api'
import auth from '../auth'
import AssignDialog from '../components/AssignDialog.vue'
import AudioPlayer from '../components/AudioPlayer.vue'
import PriorityTag from '../components/PriorityTag.vue'
import TranscriptViewer from '../components/TranscriptViewer.vue'
import SegmentTranscript from '../components/SegmentTranscript.vue'
import { formatClock } from '../utils/segments'
import {
  QUEUE_PAGE_SIZE,
  queueGlobalIndex,
  queueHasFilter,
  queuePageAfterRemoval,
  queueQuery,
  queueRemaining,
  queueTotalPages
} from '../utils/reviewQueue'
import {
  formatDuration,
  formatTime,
  formatRelative,
  priorityMeta,
  PRIORITY_OPTIONS,
  reviewProgress,
  isSampled,
  VIOLATION_TYPES,
  PROCESSING_STATUSES
} from '../constants'

const route = useRoute()
const { t, tn } = useI18n()

const tab = ref('pending')
const pendingRows = ref([])
const current = ref(null)
const aiInvocations = ref([])
const detailLoading = ref(false)
const alreadyReviewed = ref(false)
const submitting = ref(false)
const queueKeyword = ref('')
const priorityFilter = ref('')
const assignFilter = ref('')
const claiming = ref(false)
const assignVisible = ref(false)
const submittedPending = ref(null)
const reviewHistory = ref([])
const reviewHistoryLoading = ref(false)

// 复检队列分页（服务端）：page 0 基；筛选变化回第一页
const pendingPage = ref(0)
const pendingPageSize = ref(QUEUE_PAGE_SIZE)
const pendingTotal = ref(0)
const pendingLoading = ref(false)

const ASSIGN_FILTERS = computed(() => [
  { key: '', label: t('common.all') },
  { key: 'mine', label: t('review.filters.mineOnly') },
  { key: 'unassigned', label: t('review.assign.unassigned') }
])

const form = ref({ result: 'CONFIRMED_VIOLATION', violationType: 'INSULT', comment: '' })

const historyRows = ref([])
const historyTotal = ref(0)
const historyPage = ref(0)
const historyPageSize = 10
const historyLoading = ref(false)
const historyVisible = ref(false)
const historyDetail = ref(null)

// 音画联动
const playerRef = ref(null)
const currentTime = ref(0)
const follow = ref(true)
const transcriptMode = ref('segment')
/** mix | L | R：双轨录音可分声道试听 */
const audioChannel = ref('mix')
const modeInitialized = ref(false)

const channelFiles = computed(() => parseJson(current.value?.channelFilesJson, null))
const dialogueAvailable = computed(() => segments.value.some((s) => s?.channel))

const channelOptions = computed(() => {
  const files = channelFiles.value || {}
  const leftName = segments.value.find((s) => s?.channel === 'L')?.speaker || t('review.channels.left')
  const rightName = segments.value.find((s) => s?.channel === 'R')?.speaker || t('review.channels.right')
  return [
    { key: 'mix', label: t('review.channels.mix'), badge: '' },
    { key: 'L', label: files.L ? leftName : t('review.channels.left'), badge: 'L' },
    { key: 'R', label: files.R ? rightName : t('review.channels.right'), badge: 'R' }
  ]
})

/** 播放源：默认混合声道，可切到单独声道 */
const audioSrc = computed(() => {
  const files = channelFiles.value
  if (files && audioChannel.value !== 'mix' && files[audioChannel.value]) {
    return `/uploads/${files[audioChannel.value]}`
  }
  return current.value?.filePath ? `/uploads/${current.value.filePath}` : ''
})

function switchChannel(key) {
  if (audioChannel.value === key) return
  audioChannel.value = key
  currentTime.value = 0
}

/** 队列筛选是否生效：决定空态文案（没有匹配 vs 队列已清空） */
const hasQueueFilter = computed(() =>
  queueHasFilter({
    keyword: queueKeyword.value,
    priority: priorityFilter.value,
    assignFilter: assignFilter.value
  })
)

/** 队列序号：跨页连续（第 2 页第一条 = 11） */
const queueIndex = (i) =>
  String(queueGlobalIndex(pendingPage.value, pendingPageSize.value, i)).padStart(2, '0')

const remaining = computed(() => queueRemaining(pendingTotal.value))

/* ---------- 角色 / 复核工作流 ---------- */
const canReview = computed(() => auth.hasRole('ADMIN', 'REVIEWER'))
const isAdmin = computed(() => auth.hasRole('ADMIN'))
const readOnly = computed(() => !canReview.value)
/** 未认领的待复检录音可被 ADMIN/REVIEWER 认领 */
const canClaim = computed(
  () => canReview.value && current.value?.status === 'NEEDS_REVIEW' && !current.value?.assignedTo
)
/** 仅 ADMIN 可指派 */
const canAssign = computed(() => isAdmin.value && current.value?.status === 'NEEDS_REVIEW')
const progress = computed(() => reviewProgress(current.value))

/** 最近一次复核记录：用于双人复核的「同人不可连续复核」提示 */
const lastReview = computed(() => {
  const list = [...reviewHistory.value]
  list.sort((a, b) => {
    const roundDiff = (Number(b?.round) || 0) - (Number(a?.round) || 0)
    if (roundDiff) return roundDiff
    return String(b?.createdAt || '').localeCompare(String(a?.createdAt || ''))
  })
  return list[0] || null
})
const lastReviewer = computed(() => lastReview.value?.reviewerUsername || '')
const sameReviewerBlocked = computed(
  () =>
    progress.value.required > 1 &&
    progress.value.count > 0 &&
    !!lastReviewer.value &&
    lastReviewer.value === auth.username
)

const PRIORITY_DOT = { HIGH: '#ef4444', NORMAL: '#3b6ef6', LOW: '#94a3b8' }
const priorityDot = (priority) => PRIORITY_DOT[priority] || PRIORITY_DOT.NORMAL
const PRIORITY_CLASS = { HIGH: 'danger', NORMAL: 'brand', LOW: 'info' }
const priorityClass = (priority) => PRIORITY_CLASS[priority] || PRIORITY_CLASS.NORMAL

/** 队列行保持两行简洁，摘要与置信度放进原生 tooltip，信息不丢但不占版面 */
function queueTooltip(r) {
  const conf = r.aiConfidence != null ? Math.round(r.aiConfidence * 100) + '%' : '—'
  const lines = [
    `${r.aiViolationTypeLabel || t('review.queue.uncategorized')} · ${t('review.ai.confidence')} ${conf}`
  ]
  if (isSampled(r)) lines.push(t('review.queue.sampledTooltip'))
  if (r.transcriptSnippet) lines.push(r.transcriptSnippet)
  return lines.join('\n')
}

const dfaHits = computed(() => parseJson(current.value?.dfaHitsJson, []))
const aiResult = computed(() => parseJson(current.value?.aiResultJson, null))

/* ---------- AI 调用留痕 ---------- */
function messagesOf(inv) {
  try {
    return JSON.parse(inv.requestJson || '{}').messages || []
  } catch {
    return []
  }
}
function prettyJson(raw) {
  try {
    return JSON.stringify(JSON.parse(raw), null, 2)
  } catch {
    return raw
  }
}
const historyHits = computed(() => parseJson(historyDetail.value?.dfaHitsJson, []))
const segments = computed(() => parseJson(current.value?.segmentsJson, []))
/** 无 durationSeconds 时用最后一句的结束时间兜底 */
const totalDuration = computed(() => {
  const last = segments.value[segments.value.length - 1]
  return last?.end ?? 0
})

function onTimeUpdate(time) {
  currentTime.value = Number(time) || 0
}

/** 点击句子 → 跳转到对应音频时间点并播放 */
function seekTo(time) {
  if (time == null) return
  currentTime.value = time
  if (!current.value?.filePath) {
    ElMessage.info(t('review.messages.noAudioSeek', { time: formatClock(time) }))
    return
  }
  playerRef.value?.seekTo(time)
}

function parseJson(raw, fallback) {
  try {
    return JSON.parse(raw || 'null') ?? fallback
  } catch {
    return fallback
  }
}

async function fetchPendingPage() {
  const data = await api.listRecordings(
    queueQuery({
      keyword: queueKeyword.value,
      priority: priorityFilter.value,
      assignFilter: assignFilter.value,
      username: auth.username,
      page: pendingPage.value,
      size: pendingPageSize.value
    })
  )
  return { rows: data.content || [], total: data.totalElements || 0 }
}

async function loadPending() {
  pendingLoading.value = true
  try {
    let { rows, total } = await fetchPendingPage()
    // 复核完本页最后一条后本页会变空：回退一页重取，避免停在空页
    if (!rows.length && pendingPage.value > 0) {
      const pageAfter = queuePageAfterRemoval({
        page: pendingPage.value,
        pageSize: pendingPageSize.value,
        totalAfter: total
      })
      if (pageAfter !== pendingPage.value) {
        pendingPage.value = pageAfter
        ;({ rows, total } = await fetchPendingPage())
      }
    }
    pendingRows.value = rows
    pendingTotal.value = total

    const stillThere = current.value && rows.some((r) => r.id === current.value.id)
    if (!current.value || alreadyReviewed.value || !stillThere) {
      alreadyReviewed.value = false
      const target = rows.find((r) => r.id === Number(route.query.id)) || rows[0] || null
      if (target) await select(target)
      else current.value = null
    }
  } catch {
    // 请求层已提示；保留当前队列避免工作区被清空
  } finally {
    pendingLoading.value = false
  }
}

/** 关键词 / 优先级 / 指派筛选变化：回到第一页重新查询（筛选走服务端） */
function applyQueueFilters() {
  pendingPage.value = 0
  loadPending()
}

function setAssignFilter(key) {
  if (assignFilter.value === key) return
  assignFilter.value = key
  applyQueueFilters()
}

function changePendingPage(p) {
  pendingPage.value = Math.max(0, Number(p) - 1)
  loadPending()
}

/**
 * 深链 /review?id=N：目标不在当前页时直接按 id 拉详情，
 * 避免分页后「按 id 找不到就退回第一条」的误选。
 */
async function openFromRoute() {
  const id = Number(route.query.id)
  if (!id || current.value?.id === id) return
  const inPage = pendingRows.value.find((r) => r.id === id)
  if (inPage) {
    await select(inPage)
    return
  }
  try {
    const detail = await api.recordingDetail(id)
    if (detail?.status === 'NEEDS_REVIEW') await select(detail)
  } catch {
    // 详情拉取失败交给请求层提示，保留当前队列
  }
}

async function loadReviews(id) {
  if (!id) return
  reviewHistoryLoading.value = true
  try {
    reviewHistory.value = (await api.recordingReviews(id)) || []
  } catch {
    // 复核历史加载失败不阻塞复核
    reviewHistory.value = []
  } finally {
    reviewHistoryLoading.value = false
  }
}

async function select(r) {
  current.value = r
  alreadyReviewed.value = false
  submittedPending.value = null
  reviewHistory.value = []
  form.value = { result: 'CONFIRMED_VIOLATION', violationType: 'INSULT', comment: '' }
  // 切换录音时重置播放进度与视图模式
  currentTime.value = 0
  audioChannel.value = 'mix'
  aiInvocations.value = []
  detailLoading.value = true
  reviewHistoryLoading.value = true
  try {
    const [detail, invocations, reviews] = await Promise.all([
      api.recordingDetail(r.id),
      api.recordingAiInvocations(r.id).catch(() => []),
      api.recordingReviews(r.id).catch(() => [])
    ])
    current.value = detail
    aiInvocations.value = invocations || []
    reviewHistory.value = reviews || []
    alreadyReviewed.value = detail.status !== 'NEEDS_REVIEW'
    // 首次遇到双轨录音默认用双栏对话；若当前模式不适用则回退逐句
    if (dialogueAvailable.value && !modeInitialized.value) {
      transcriptMode.value = 'dialogue'
    }
    if (transcriptMode.value === 'dialogue' && !dialogueAvailable.value) {
      transcriptMode.value = 'segment'
    }
    modeInitialized.value = true
    // 默认选中 AI 建议的违规类型，减少复检操作成本
    const suggested = parseJson(detail.aiResultJson, null)?.violationType
    if (suggested && VIOLATION_TYPES[suggested]) {
      form.value.violationType = suggested
    }
  } catch {
    // 详情加载失败时保留列表项，避免工作区空白
  } finally {
    detailLoading.value = false
    reviewHistoryLoading.value = false
  }
}

/** 认领当前录音（ADMIN / REVIEWER）：未认领的 NEEDS_REVIEW 才可认领 */
async function claimCurrent() {
  const rec = current.value
  if (!rec?.id || !canClaim.value) return
  claiming.value = true
  try {
    const saved = await api.claimRecording(rec.id)
    const assignedTo = saved?.assignedTo || auth.username
    current.value = { ...rec, ...(saved && typeof saved === 'object' ? saved : {}), assignedTo }
    pendingRows.value = pendingRows.value.map((row) =>
      row.id === rec.id ? { ...row, assignedTo } : row
    )
    ElMessage.success(t('review.messages.claimed'))
  } catch {
    // 409（已被他人认领）等由请求层统一提示
  } finally {
    claiming.value = false
  }
}

/** 指派成功：同步当前录音与队列中的指派信息 */
function onAssigned({ username }) {
  const id = current.value?.id
  if (!id) return
  current.value = { ...current.value, assignedTo: username }
  pendingRows.value = pendingRows.value.map((row) =>
    row.id === id ? { ...row, assignedTo: username } : row
  )
}

async function submit() {
  if (!canReview.value) {
    ElMessage.warning(t('review.messages.readOnlySubmit'))
    return
  }
  if (sameReviewerBlocked.value) {
    ElMessage.warning(t('review.messages.sameReviewerSubmit'))
    return
  }
  if (form.value.result === 'CONFIRMED_VIOLATION' && !form.value.violationType) {
    ElMessage.warning(t('review.messages.selectViolationType'))
    return
  }
  submitting.value = true
  try {
    const reviewId = current.value.id
    // 复核人由后端登录态决定，请求体不再携带 reviewer
    const saved = await api.review(reviewId, {
      result: form.value.result,
      violationType: form.value.violationType,
      comment: form.value.comment
    })
    // 以详情为准刷新状态与复核进度（提交接口可能只返回当前状态）
    const detail = await api.recordingDetail(reviewId).catch(() => null)
    const prevProgress = reviewProgress(current.value)
    const merged = {
      ...current.value,
      ...(saved && typeof saved === 'object' ? saved : {}),
      ...(detail || {})
    }
    current.value = merged
    let savedProgress = reviewProgress(merged)
    // 后端未回传复核计数时的兜底：至少按 +1 处理，避免误判为「未提交」
    if (merged.reviewCount == null && saved?.reviewCount == null) {
      savedProgress = reviewProgress({
        reviewCount: prevProgress.count + 1,
        requiredApprovals: merged.requiredApprovals ?? prevProgress.required
      })
    }
    await loadReviews(reviewId)

    if (merged.status === 'NEEDS_REVIEW' && savedProgress.count < savedProgress.required) {
      // 双人复核：本轮已提交，等待下一位复核人
      submittedPending.value = savedProgress
      ElMessage.success(t('review.messages.submittedPending', { progress: savedProgress.text }))
    } else {
      alreadyReviewed.value = true
      ElMessage.success(
        form.value.result === 'CONFIRMED_VIOLATION'
          ? t('review.messages.confirmedSaved')
          : t('review.messages.normalSaved')
      )
      await loadPending()
    }
    if (tab.value === 'history') loadHistory()
  } catch {
    // 409（终态/同一人重复复核）等由请求层统一提示
  } finally {
    submitting.value = false
  }
}

function autoNext() {
  const rows = pendingRows.value
  const idx = rows.findIndex((r) => r.id === current.value?.id)
  if (idx >= 0 && idx + 1 < rows.length) {
    select(rows[idx + 1])
    return
  }
  // 本页到底：还有下一页就翻页，否则回到本页第一条
  if (pendingPage.value + 1 < queueTotalPages(pendingTotal.value, pendingPageSize.value)) {
    pendingPage.value += 1
    loadPending()
    return
  }
  const first = rows.find((r) => r.id !== current.value?.id)
  if (first) select(first)
  else loadPending()
}

async function loadHistory() {
  historyLoading.value = true
  try {
    const data = await api.listRecordings({
      statuses: ['VIOLATION_CONFIRMED', 'FALSE_POSITIVE'],
      page: historyPage.value,
      size: historyPageSize
    })
    historyRows.value = data.content || []
    historyTotal.value = data.totalElements || 0
  } finally {
    historyLoading.value = false
  }
}

async function openHistoryDetail(row) {
  historyDetail.value = await api.recordingDetail(row.id)
  historyVisible.value = true
}

function switchTab(name) {
  tab.value = name
  if (name === 'history') loadHistory()
  else loadPending()
}

let timer = null
onMounted(() => {
  loadPending().then(openFromRoute)
  loadHistory()
  timer = setInterval(() => {
    if (tab.value === 'pending' && pendingRows.value.some((r) => PROCESSING_STATUSES.includes(r.status))) {
      loadPending()
    }
  }, 5000)
})
onUnmounted(() => clearInterval(timer))

watch(
  () => route.query.id,
  () => {
    if (tab.value === 'pending') loadPending().then(openFromRoute)
  }
)
</script>

<style scoped>
.topbar {
  display: flex;
  flex-direction: column;
  gap: 9px;
  padding: 14px 18px;
}
.topbar-main {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  row-gap: 10px;
}
.topbar-hint {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--el-text-color-secondary);
}

/* 分段控件 */
.seg {
  display: inline-flex;
  background: var(--ink-100);
  border-radius: 10px;
  padding: 2px;
  gap: 3px;
}
.seg-item {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  height: 28px;
  padding: 0 14px;
  border: none;
  border-radius: 8px;
  background: transparent;
  color: var(--el-text-color-regular);
  font-size: 13px;
  font-family: inherit;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.18s ease;
}
.seg-item.on {
  background: #fff;
  color: var(--brand-600);
  box-shadow: var(--sh-xs);
}
.seg-count {
  min-width: 18px;
  height: 18px;
  padding: 0 5px;
  border-radius: 999px;
  background: var(--ink-200);
  color: var(--ink-500);
  font-size: 11px;
  font-weight: 600;
  display: grid;
  place-items: center;
}
.seg-count.hot {
  background: var(--danger);
  color: #fff;
}

/* 工作区 */
.workspace {
  display: grid;
  grid-template-columns: 320px minmax(0, 1fr);
  gap: 16px;
  align-items: start;
}
/* 1024 等窄桌面仍保持「队列 + 详情」并排（队列固定 320px），
   详情列变窄时由 .work-grid 断点把右侧结论块换行，而不是整页堆叠出大片空白；
   只有真正的小屏（<820，侧栏已切抽屉）才上下堆叠。 */
@media (max-width: 820px) {
  .workspace {
    grid-template-columns: minmax(0, 1fr);
  }
  /* 窄屏（含侧边栏浏览器面板）上下堆叠时，队列别把工作区挤出首屏 */
  .queue {
    max-height: 38vh;
  }
}

/* 队列 */
.queue {
  display: flex;
  flex-direction: column;
  max-height: calc(100vh - 190px);
}
.queue-head {
  padding: 15px 18px 12px;
  border-bottom: 1px solid var(--border);
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.queue-search {
  width: 100%;
}
.queue-list {
  overflow-y: auto;
  padding: 8px 9px 12px;
  display: flex;
  flex-direction: column;
  gap: 6px;
  /* 队列列表独立滚动，分页底栏固定可见 */
  flex: 1;
  min-height: 0;
}
/* 队列分页底栏：共 N 条 + 翻页 */
.queue-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 8px 12px;
  border-top: 1px solid var(--border);
}
.queue-total {
  flex: none;
  font-size: 11px;
  color: var(--ink-400);
  white-space: nowrap;
}
.queue-foot :deep(.el-pagination) {
  --el-pagination-button-width: 24px;
  --el-pagination-button-height: 24px;
  --el-pagination-font-size: 12px;
  gap: 2px;
}
.queue-item {
  display: flex;
  gap: 8px;
  align-items: center;
  width: 100%;
  text-align: left;
  padding: 9px 8px;
  border-radius: var(--r-sm);
  border: 1px solid transparent;
  background: #fff;
  font-family: inherit;
  cursor: pointer;
  transition: background 0.15s ease, border-color 0.15s ease;
}
.queue-item:hover {
  background: var(--ink-50);
}
.queue-item.on {
  background: var(--brand-50);
  border-color: var(--brand-200);
}
.queue-index {
  flex: none;
  width: 18px;
  font-size: 11px;
  color: var(--ink-400);
  font-weight: 600;
}
.queue-item.on .queue-index {
  color: var(--brand-500);
}
.queue-body {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.queue-name {
  font-size: 12.5px;
  font-weight: 500;
  color: var(--ink-700);
  line-height: 1.45;
}
.queue-meta {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 11px;
  line-height: 1.4;
}
.queue-type {
  color: #b06a06;
  font-weight: 500;
}
.queue-time {
  color: var(--el-text-color-placeholder);
}
.queue-time::before {
  content: "·";
  margin-right: 6px;
  color: var(--ink-300);
}
/* 工作区主体 */
.work {
  padding: 18px;
  min-height: 420px;
}
.work-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}
.work-name {
  font-size: 16px;
  font-weight: 600;
  color: var(--ink-800);
  max-width: 620px;
  line-height: 1.45;
}
.work-chips {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
  margin-top: 10px;
}
.work-actions {
  flex: none;
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
  flex-wrap: wrap;
}
/* 窄屏：文件名 + 操作按钮挤在一行会把标题压成 0 宽、chips 竖排，
   改成上下两段，操作按钮左对齐换行 */
@media (max-width: 900px) {
  .work-head {
    flex-direction: column;
    align-items: stretch;
    gap: 10px;
  }
  .work-actions {
    justify-content: flex-start;
  }
}
.player-row {
  display: flex;
  align-items: center;
  gap: 12px;
  row-gap: 8px;
  flex-wrap: wrap;
  margin: 16px 0 0;
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

/* 分声道试听切换 */
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

/* 逐句 / 全文切换 */
.block-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 11px;
}
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

.work-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.25fr) minmax(320px, 0.95fr);
  gap: 18px;
  margin-top: 16px;
}
/* 详情列窄于 ~740px 时，逐句对照与 AI 结论改为上下排列，避免两栏都过窄 */
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

.block-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--ink-700);
  margin-bottom: 11px;
}
.mt-14 {
  margin-top: 14px;
}

/* AI 判断 */
.verdict {
  border: 1px solid var(--border);
  border-radius: var(--r-md);
  padding: 16px;
}
.verdict.bad {
  background: linear-gradient(180deg, #fff6f6, #fffdfd);
  border-color: #f7d0d0;
}
.verdict.ok {
  background: linear-gradient(180deg, #f5fbf7, #fdfefd);
  border-color: #cfe9d8;
}
.verdict-head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.conf {
  display: inline-flex;
  align-items: baseline;
  gap: 4px;
  margin-left: auto;
}
.conf b {
  font-size: 13px;
  color: var(--ink-700);
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
  color: #12823b;
}
.conf-track {
  height: 6px;
  border-radius: 999px;
  background: rgba(15, 23, 42, 0.07);
  margin: 12px 0 10px;
  overflow: hidden;
}
.conf-fill {
  height: 100%;
  border-radius: 999px;
  transition: width 0.5s ease;
}
.conf-fill.bad {
  background: linear-gradient(90deg, #f87171, #ef4444);
}
.conf-fill.ok {
  background: linear-gradient(90deg, #4ade80, #16a34a);
}
.verdict-reason {
  font-size: 12.5px;
  line-height: 1.7;
  color: var(--el-text-color-regular);
}

/* 判定模型标注 */
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

/* AI 调用留痕 */
.ai-collapse {
  margin-top: 10px;
  border-top: 1px dashed var(--border);
  border-bottom: none;
}
.ai-toggle {
  color: var(--brand-600);
  font-weight: 600;
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
.ai-part {
  margin-top: 10px;
}
.ai-part-head {
  margin-bottom: 5px;
}
.ai-role {
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
  padding: 9px 11px;
  max-height: 240px;
  overflow: auto;
  background: var(--ink-50);
  border: 1px solid var(--border);
  border-radius: var(--r-sm);
  font-family: var(--font-mono);
  font-size: 11px;
  line-height: 1.65;
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

/* 结论表单 */
.form {
  margin-top: 0;
  border: 1px solid var(--border);
  border-radius: var(--r-md);
  padding: 16px;
}
.verdict-choice {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 9px;
  margin-bottom: 13px;
}
.choice {
  height: 40px;
  border-radius: var(--r-sm);
  border: 1px solid var(--border);
  background: #fff;
  color: var(--el-text-color-regular);
  font-size: 13px;
  font-family: inherit;
  font-weight: 500;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  transition: all 0.16s ease;
}
.choice:hover {
  border-color: var(--brand-300);
}
.choice.on.bad {
  background: var(--danger-soft);
  border-color: #f0a9a9;
  color: #c92c2c;
}
.choice.on.ok {
  background: var(--ok-soft);
  border-color: #a9d9bc;
  color: #12823b;
}

.types {
  margin-bottom: 13px;
}
.types-label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-bottom: 7px;
}
.types-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.type-chip {
  height: 26px;
  padding: 0 11px;
  border-radius: 999px;
  border: 1px solid var(--border);
  background: #fff;
  color: var(--el-text-color-regular);
  font-size: 12px;
  font-family: inherit;
  cursor: pointer;
  transition: all 0.16s ease;
}
.type-chip:hover {
  border-color: var(--brand-300);
  color: var(--brand-600);
}
.type-chip.on {
  background: var(--brand-500);
  border-color: var(--brand-500);
  color: #fff;
}
.submit {
  width: 100%;
  margin-top: 13px;
}

/* 已出结论面板 */
.reviewed {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 46px 20px;
  text-align: center;
}
.icon-bad {
  color: var(--danger);
}
.icon-ok {
  color: var(--ok);
}
.reviewed-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--ink-800);
}
.reviewed-sub {
  font-size: 12.5px;
  color: var(--el-text-color-secondary);
  max-width: 460px;
  line-height: 1.7;
  margin-bottom: 6px;
}

/* 历史 */
.file-cell {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}
.file-icon {
  width: 30px;
  height: 30px;
  flex: none;
  border-radius: 9px;
  display: grid;
  place-items: center;
  background: var(--brand-50);
  color: var(--brand-600);
}
.min-w-0 {
  min-width: 0;
}
.ellipsis {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.pager {
  display: flex;
  justify-content: flex-end;
  padding: 12px 18px;
}
.empty-icon {
  color: var(--ink-300);
}
.empty-title {
  margin: 8px 0 4px;
  font-weight: 600;
  color: var(--ink-600);
}
.comment-block {
  margin-top: 14px;
  padding: 11px 13px;
  border-radius: var(--r-md);
  background: var(--ink-50);
  border: 1px solid var(--border);
  font-size: 12.5px;
  line-height: 1.7;
  color: var(--el-text-color-regular);
}
/* 队列筛选 + 队列行工作流标记 */
.queue-filters {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.prio-select {
  width: 128px;
}
.assign-filter {
  display: inline-flex;
  background: var(--ink-100);
  border-radius: 9px;
  padding: 3px;
  gap: 3px;
}
.af-btn {
  height: 26px;
  padding: 0 12px;
  border: none;
  border-radius: 7px;
  background: transparent;
  color: var(--el-text-color-regular);
  font-size: 12.5px;
  font-family: inherit;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.16s ease;
  white-space: nowrap;
}
.af-btn:hover {
  color: var(--brand-600);
}
.af-btn.on {
  background: #fff;
  color: var(--brand-600);
  box-shadow: var(--sh-xs);
}
.queue-logic {
  min-width: 0;
}
.queue-meta {
  flex-wrap: wrap;
}
.queue-prio {
  font-size: 10px;
  font-weight: 600;
  height: 15px;
  line-height: 15px;
  padding: 0 5px;
  border-radius: 4px;
  background: var(--ink-100);
  color: var(--ink-500);
}
.queue-prio.danger {
  background: var(--danger-soft);
  color: #c92c2c;
}
.queue-prio.brand {
  background: var(--brand-50);
  color: var(--brand-600);
}
.queue-prio.info {
  background: var(--ink-100);
  color: var(--ink-500);
}
.queue-assignee {
  max-width: 76px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  padding: 0 5px;
  border-radius: 4px;
  background: var(--ink-100);
  color: var(--ink-500);
}
.queue-assignee.me {
  background: var(--ok-soft);
  color: #12823b;
}
.queue-assignee.unassigned {
  padding: 0;
  background: transparent;
  color: var(--ink-400);
}
/* 抽样标记：DFA 未命中后按比例进入复检 */
.queue-sampled {
  font-size: 10px;
  font-weight: 600;
  height: 15px;
  line-height: 15px;
  padding: 0 5px;
  border-radius: 4px;
  background: var(--warn-soft);
  color: #b06a06;
  white-space: nowrap;
  flex: none;
}

/* 复核进度 / 复核历史 */
.workflow {
  margin-top: 0;
  border: 1px solid var(--border);
  border-radius: var(--r-md);
  padding: 16px 16px 10px;
}
.no-mb {
  margin-bottom: 0;
}
.progress-row {
  display: flex;
  align-items: center;
  gap: 6px;
  margin: 10px 0 12px;
}
.progress-dot {
  width: 12px;
  height: 12px;
  flex: none;
  border-radius: 50%;
  border: 1.5px solid var(--ink-300);
  background: #fff;
}
.progress-dot.on {
  background: var(--brand-500);
  border-color: var(--brand-500);
}
.progress-row b {
  margin-left: 2px;
  font-size: 13px;
  color: var(--brand-600);
}
.wf-alert {
  margin-bottom: 12px;
}
.history-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin: 2px 0 10px;
  padding-top: 10px;
  border-top: 1px dashed var(--border);
}
.review-timeline {
  padding-left: 2px;
  padding-top: 2px;
}
.review-timeline :deep(.el-timeline-item) {
  padding-bottom: 14px;
}
.rh-line {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  font-size: 12.5px;
}
.rh-comment {
  margin-top: 4px;
  font-size: 12px;
  line-height: 1.65;
  color: var(--el-text-color-regular);
}

/* 只读 / 同人复核提示 */
.readonly-note {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  margin-top: 0;
  padding: 14px 16px;
  border-radius: var(--r-md);
  background: var(--ink-50);
  border: 1px dashed var(--border-strong);
  color: var(--el-text-color-secondary);
  font-size: 12.5px;
  line-height: 1.65;
}
.form-warn {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 12px;
  font-size: 12px;
  color: #b06a06;
}
.icon-warn {
  color: var(--warn);
}
</style>
