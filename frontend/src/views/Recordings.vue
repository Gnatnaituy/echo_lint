<template>
  <div class="page stack">
    <!-- 工具栏 -->
    <section class="panel">
      <div class="toolbar">
        <div class="filters" role="group" aria-label="状态筛选">
          <button
            v-for="f in filters"
            :key="f.key"
            class="filter-chip"
            :class="{ on: statusFilter === f.key }"
            @click="applyFilter(f.key)"
          >
            {{ f.label }}
            <span v-if="f.count !== null" class="filter-count">{{ f.count }}</span>
          </button>
        </div>

        <div class="actions">
          <span v-if="!canWrite" class="chip info read-only-chip">只读模式</span>
          <el-select
            v-model="assignedFilter"
            placeholder="全部指派"
            clearable
            class="filter-select"
            @change="reload"
          >
            <el-option label="只看我的" value="mine" />
          </el-select>
          <el-select
            v-model="priorityFilter"
            placeholder="全部优先级"
            clearable
            class="priority-select"
            @change="reload"
          >
            <el-option
              v-for="opt in PRIORITY_OPTIONS"
              :key="opt.value"
              :label="`${opt.label}优先级`"
              :value="opt.value"
            />
          </el-select>
          <el-input
            v-model="keyword"
            placeholder="搜索文件名 / 转写内容"
            clearable
            class="search"
            @keyup.enter="reload"
            @clear="reload"
          >
            <template #prefix>
              <el-icon><Search /></el-icon>
            </template>
          </el-input>
          <el-button :icon="Refresh" circle @click="reload" aria-label="刷新" title="刷新" />
          <el-button
            v-if="canWrite"
            type="primary"
            :icon="UploadFilled"
            @click="openUpload"
          >上传录音</el-button>
        </div>
      </div>

      <!-- 元数据筛选：通话日期范围 / 坐席 / 团队 / 方向（与后端 query 参数一致） -->
      <div class="meta-filters">
        <el-date-picker
          v-model="callDateRange"
          type="daterange"
          value-format="YYYY-MM-DD"
          unlink-panels
          range-separator="至"
          start-placeholder="通话开始日期"
          end-placeholder="通话结束日期"
          :shortcuts="dateShortcuts"
          class="date-range"
          @change="reload"
        />
        <el-input
          v-model="agentFilter"
          placeholder="坐席"
          clearable
          class="meta-input"
          @keyup.enter="reload"
          @clear="reload"
        />
        <el-input
          v-model="teamFilter"
          placeholder="团队"
          clearable
          class="meta-input"
          @keyup.enter="reload"
          @clear="reload"
        />
        <el-select
          v-model="directionFilter"
          placeholder="全部方向"
          clearable
          class="direction-select"
          @change="reload"
        >
          <el-option
            v-for="opt in DIRECTION_OPTIONS"
            :key="opt.value"
            :label="opt.label"
            :value="opt.value"
          />
        </el-select>
        <el-button
          v-if="hasMetaFilters"
          class="meta-clear"
          link
          type="primary"
          @click="resetMetaFilters"
        >清除条件</el-button>
      </div>
      <div class="hint">
        <template v-if="canWrite">
          <p class="hint-line">
            上传时可填写坐席、团队、通话时间、方向与业务线，列表与报表可按这些维度筛选；上传后自动执行 Whisper 转写 → DFA 初筛 → AI 语义复筛，初筛与复筛<span class="accent">均判定违规</span>的录音进入人工复检。单文件 ≤ 25MB。
          </p>
          <p class="hint-line">
            若录音卡在「待处理 / 处理中」超过 2 分钟（例如上传后服务重启导致流水线丢失），可用行内<span class="accent">推进处理 / 重新处理</span>手动触发。
          </p>
        </template>
        <p v-else class="hint-line">
          只读账号可查看录音、转写与 AI 判定详情；上传、删除、认领与指派等写操作不可用。
        </p>
      </div>
    </section>

    <!-- 列表 -->
    <section ref="tableWrap" class="panel">
      <el-table
        v-loading="loading"
        :data="rows"
        :row-class-name="() => 'row-clickable'"
        scrollbar-always-on
        @row-click="openDetail"
      >
        <el-table-column label="录音" min-width="188">
          <template #default="{ row }">
            <div class="file-cell">
              <div
                class="file-icon"
                :class="{ sampled: isSampled(row) }"
                :title="isSampled(row) ? 'DFA 未命中，按抽样比例进入 AI 语义复筛 / 人工复检' : undefined"
              >
                <el-icon><Microphone /></el-icon>
                <span v-if="isSampled(row)" class="sample-dot" aria-hidden="true"></span>
              </div>
              <div class="file-meta">
                <div class="file-name ellipsis" :title="row.fileName">{{ row.fileName }}</div>
                <div class="file-sub">
                  <span class="mono">#{{ row.id }}</span>
                  <span class="sep">·</span>
                  <span class="num">{{ formatDuration(row.durationSeconds) }}</span>
                  <span class="sep">·</span>
                  <span class="num">{{ formatTimeShort(row.uploadTime) }}</span>
                </div>
              </div>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="坐席 / 团队" width="100">
          <template #default="{ row }">
            <div class="agent-cell">
              <div class="agent-line">
                <span v-if="row.agentName" class="cell-ellipsis" :title="row.agentName">{{ row.agentName }}</span>
                <span v-else class="dim">—</span>
              </div>
              <div class="agent-line sub">
                <span v-if="row.teamName" class="cell-ellipsis" :title="row.teamName">{{ row.teamName }}</span>
                <span v-else class="dim">—</span>
              </div>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="通话时间" width="102">
          <template #default="{ row }">
            <div class="call-cell">
              <span v-if="row.callTime" class="num call-time" :title="formatTime(row.callTime)">
                {{ formatTimeShort(row.callTime) }}
              </span>
              <span v-else class="dim">—</span>
              <span
                v-if="row.direction && DIRECTION_META[row.direction] && row.direction !== 'UNKNOWN'"
                class="chip dir-chip"
                :class="DIRECTION_META[row.direction].type"
              >{{ DIRECTION_META[row.direction].label }}</span>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="DFA 命中" width="86" align="center" class-name="col-tight" label-class-name="col-tight">
          <template #default="{ row }">
            <span v-if="row.hitCount" class="chip danger hit-chip">
              <el-icon :size="10"><WarningFilled /></el-icon>{{ row.hitCount }} 处
            </span>
            <span v-else class="dim">—</span>
          </template>
        </el-table-column>

        <el-table-column label="AI 复筛结论" width="104" align="center" class-name="col-tight" label-class-name="col-tight">
          <template #default="{ row }">
            <span v-if="row.aiViolationTypeLabel" class="chip warn">{{ row.aiViolationTypeLabel }}</span>
            <span v-else-if="row.aiViolation === true" class="chip danger">违规</span>
            <span v-else-if="row.aiViolation === false" class="chip ok">判定正常</span>
            <span v-else-if="row.hitCount && PROCESSING_STATUSES.includes(row.status)" class="chip info">复筛中</span>
            <span v-else class="dim">—</span>
          </template>
        </el-table-column>

        <el-table-column v-if="!compactColumns" label="优先级" width="84" align="center" class-name="col-tight" label-class-name="col-tight">
          <template #default="{ row }">
            <PriorityTag :priority="row.priority" class="prio-cell" />
          </template>
        </el-table-column>

        <el-table-column v-if="!narrowColumns" label="指派" width="80" align="center" class-name="col-tight" label-class-name="col-tight">
          <template #default="{ row }">
            <span
              v-if="row.assignedTo"
              class="chip assignee-chip"
              :class="{ brand: row.assignedTo === auth.username }"
              :title="row.assignedTo"
            >{{ row.assignedTo === auth.username ? '我' : row.assignedTo }}</span>
            <span v-else class="dim">未指派</span>
          </template>
        </el-table-column>

        <el-table-column label="状态 / 复检人" width="122" align="center">
          <template #default="{ row }">
            <div class="status-cell">
              <div class="status-line">
                <StatusTag :status="row.status" />
                <el-tooltip
                  v-if="isStale(row)"
                  content="停留超过 2 分钟未变更，疑似中断；可用「重新处理」手动推进"
                  placement="top"
                >
                  <el-icon class="stale-icon" :size="13"><WarningFilled /></el-icon>
                </el-tooltip>
              </div>
              <el-tooltip
                v-if="row.reviewer"
                :content="`${row.reviewResult === 'CONFIRMED_VIOLATION' ? '确认违规' : '误判放行'} · ${formatTime(row.reviewTime)}`"
                placement="top"
              >
                <span class="reviewer-line">复检 · {{ row.reviewer }}</span>
              </el-tooltip>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="操作" width="152" align="right" fixed="right">
          <template #default="{ row }">
            <div class="row-actions">
              <el-button class="op-link" link type="primary" @click.stop="openDetail(row)">详情</el-button>
              <el-button
                v-if="row.status === 'NEEDS_REVIEW'"
                class="op-link"
                link
                type="danger"
                @click.stop="$router.push(`/review?id=${row.id}`)"
              >去复检</el-button>
              <el-popover
                v-if="canWrite"
                trigger="click"
                placement="bottom-end"
                :width="168"
                popper-class="rec-more-popper"
              >
                <template #reference>
                  <button type="button" class="more-btn" @click.stop>
                    更多
                    <el-icon :size="11"><ArrowDown /></el-icon>
                  </button>
                </template>
                <div class="more-list" @click.stop>
                  <button v-if="canClaimRow(row)" class="more-item" @click.stop="claim(row)">认领</button>
                  <button
                    v-if="isAdmin && row.status === 'NEEDS_REVIEW'"
                    class="more-item"
                    @click.stop="openAssign(row)"
                  >指派复核人</button>
                  <el-popconfirm
                    v-if="isAdmin && isFinalReviewStatus(row.status)"
                    title="重开为待人工复检？将清空最终结论并保留复核历史。"
                    width="260"
                    @confirm="reopen(row)"
                  >
                    <template #reference>
                      <button type="button" class="more-item warn" @click.stop>重开复检</button>
                    </template>
                  </el-popconfirm>
                  <el-popconfirm
                    v-if="canWrite && reprocessInfo(row)"
                    :title="reprocessInfo(row).confirm"
                    width="260"
                    @confirm="reprocess(row)"
                  >
                    <template #reference>
                      <button
                        type="button"
                        class="more-item"
                        :class="`tone-${reprocessInfo(row).tone}`"
                        @click.stop
                      >{{ reprocessInfo(row).label }}</button>
                    </template>
                  </el-popconfirm>
                  <el-popconfirm
                    v-if="canWrite"
                    title="确认删除该录音（含文件与日志）？"
                    @confirm="remove(row)"
                  >
                    <template #reference>
                      <button type="button" class="more-item danger" @click.stop>删除录音</button>
                    </template>
                  </el-popconfirm>
                </div>
              </el-popover>
            </div>
          </template>
        </el-table-column>

        <template #empty>
          <div class="empty-block">
            <el-icon :size="34" class="empty-icon"><Microphone /></el-icon>
            <div class="empty-title">还没有录音</div>
            <div class="tiny dim">上传客服通话录音，系统会自动完成转写、初筛与语义复筛</div>
          </div>
        </template>
      </el-table>

      <div class="pager">
        <el-pagination
          layout="total, prev, pager, next"
          :total="total"
          :page-size="pageSize"
          :current-page="page + 1"
          @current-change="(p) => { page = p - 1; load() }"
        />
      </div>
    </section>

    <!-- 详情抽屉 -->
    <el-drawer v-model="detailVisible" size="720px" :with-header="false" append-to-body>
      <div v-loading="detailLoading" class="detail">
        <template v-if="detail">
          <!-- 抽屉头 -->
          <div class="detail-head">
            <div class="detail-title">
              <div class="file-icon lg"><el-icon :size="18"><Microphone /></el-icon></div>
              <div class="min-w-0">
                <div class="detail-name ellipsis">{{ detail.fileName }}</div>
                <div class="detail-chips">
                  <StatusTag :status="detail.status" />
                  <span class="chip">#{{ detail.id }}</span>
                  <span class="chip">{{ formatDuration(detail.durationSeconds) }}</span>
                  <span class="chip">{{ formatSize(detail.fileSize) }}</span>
                  <span v-if="detail.language" class="chip">{{ detail.language }}</span>
                  <span v-if="detail.channelCount === 2" class="chip brand">双声道双轨</span>
                  <span
                    v-if="isSampled(detail)"
                    class="chip warn"
                    title="DFA 未命中，按抽样比例进入 AI 语义复筛 / 人工复检"
                  >抽样录音</span>
                  <PriorityTag :priority="detail.priority" />
                  <span class="chip">
                    {{ detail.assignedTo ? `指派：${detail.assignedTo}` : '未指派' }}
                  </span>
                  <span v-if="detail.agentName" class="chip">坐席：{{ detail.agentName }}</span>
                  <span v-if="detail.teamName" class="chip">团队：{{ detail.teamName }}</span>
                  <span v-if="detail.businessLine" class="chip">业务线：{{ detail.businessLine }}</span>
                  <span
                    v-if="detail.direction && DIRECTION_META[detail.direction]"
                    class="chip"
                    :class="directionClass(detail.direction)"
                  >{{ directionLabel(detail.direction) }}</span>
                  <span v-if="detail.callTime" class="chip num">通话：{{ formatTime(detail.callTime) }}</span>
                  <span v-if="detailProgress.required > 1" class="chip info">
                    复核 {{ detailProgress.text }}
                  </span>
                </div>
              </div>
            </div>
            <div class="detail-head-actions">
              <el-button
                v-if="detail.status === 'NEEDS_REVIEW'"
                type="danger"
                size="small"
                @click="$router.push(`/review?id=${detail.id}`)"
              >去人工复检</el-button>
              <el-button
                v-if="canClaimRow(detail)"
                type="primary"
                size="small"
                @click="claim(detail)"
              >认领</el-button>
              <el-button
                v-if="isAdmin && detail.status === 'NEEDS_REVIEW'"
                size="small"
                @click="openAssign(detail)"
              >指派</el-button>
              <el-popconfirm
                v-if="isAdmin && isFinalReviewStatus(detail.status)"
                title="重开为待人工复检？将清空最终结论并保留复核历史。"
                width="260"
                @confirm="reopen(detail)"
              >
                <template #reference>
                  <el-button type="warning" size="small">重开</el-button>
                </template>
              </el-popconfirm>
              <el-popconfirm
                v-if="canWrite && reprocessInfo(detail)"
                :title="reprocessInfo(detail).confirm"
                width="260"
                @confirm="reprocess(detail)"
              >
                <template #reference>
                  <el-button :type="reprocessInfo(detail).tone" size="small">
                    {{ reprocessInfo(detail).label }}
                  </el-button>
                </template>
              </el-popconfirm>
              <el-button :icon="Close" circle size="small" @click="detailVisible = false" aria-label="关闭" title="关闭" />
            </div>
          </div>

          <!-- 录音播放 + 分声道试听 -->
          <div class="player-row">
            <AudioPlayer
              v-if="audioUrl"
              ref="playerRef"
              :src="audioSrc"
              :fallback-duration="detail.durationSeconds || 0"
              @time-update="onTimeUpdate"
            />
            <div v-else class="no-audio tiny dim">该录音无可播放文件（演示数据不含音频）</div>
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

          <!-- 失败原因 -->
          <div v-if="detail.errorMessage" class="error-banner">
            <el-icon><CircleCloseFilled /></el-icon>
            <div>{{ detail.errorMessage }}</div>
          </div>

          <!-- AI 复筛 -->
          <section v-if="aiResult" class="block">
            <div class="block-title">AI 语义复筛</div>
            <div class="verdict" :class="aiResult.violation ? 'bad' : 'ok'">
              <div class="verdict-head">
                <span class="verdict-badge">
                  <el-icon :size="14">
                    <component :is="aiResult.violation ? 'WarningFilled' : 'CircleCheckFilled'" />
                  </el-icon>
                  {{ aiResult.violation ? '判定违规' : '判定正常' }}
                </span>
                <span v-if="aiTypeLabel" class="chip warn">{{ aiTypeLabel }}</span>
                <span class="spacer"></span>
                <span class="conf">
                  置信度
                  <b class="num">{{ aiResult.confidence != null ? (aiResult.confidence * 100).toFixed(0) + '%' : '—' }}</b>
                </span>
              </div>
              <div class="conf-track">
                <div
                  class="conf-fill"
                  :class="aiResult.violation ? 'bad' : 'ok'"
                  :style="{ width: (aiResult.confidence || 0) * 100 + '%' }"
                ></div>
              </div>
              <div class="verdict-reason">{{ aiResult.reason || '—' }}</div>
              <div v-if="aiResult.screenModel" class="verdict-model tiny">
                <span class="dim">判定模型</span>
                <span class="mono">{{ aiResult.screenProviderLabel || aiResult.screenProvider }} · {{ aiResult.screenModel }}</span>
                <span v-if="aiResult.degraded" class="chip warn">降级转人工</span>
              </div>
            </div>
          </section>

          <!-- 转写 -->
          <section class="block">
            <div class="block-head">
              <div class="seg-toggle">
                <button
                  v-if="dialogueAvailable"
                  class="seg-btn"
                  :class="{ on: transcriptMode === 'dialogue' }"
                  @click="transcriptMode = 'dialogue'"
                >
                  <el-icon :size="12"><Connection /></el-icon>
                  双栏对话
                </button>
                <button
                  class="seg-btn"
                  :class="{ on: transcriptMode === 'segment' }"
                  @click="transcriptMode = 'segment'"
                >
                  <el-icon :size="12"><Tickets /></el-icon>
                  逐句对照
                </button>
                <button
                  class="seg-btn"
                  :class="{ on: transcriptMode === 'full' }"
                  @click="transcriptMode = 'full'"
                >
                  <el-icon :size="12"><Document /></el-icon>
                  全文
                </button>
              </div>
              <label v-if="transcriptMode !== 'full'" class="follow">
                <el-switch v-model="follow" size="small" />
                <span class="tiny dim">跟随播放</span>
              </label>
            </div>

            <SegmentTranscript
              v-if="transcriptMode !== 'full'"
              :text="detail.transcript || ''"
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
              :text="detail.transcript || '（暂无转写文本）'"
              :hits="dfaHits"
              :target-sentence="aiResult?.targetSentence"
            />
          </section>

          <!-- 人工复检 -->
          <section v-if="detail.reviewResult" class="block">
            <div class="block-title">人工复检</div>
            <div class="review-grid">
              <div class="review-item">
                <span class="review-label">结论</span>
                <span
                  class="chip"
                  :class="detail.reviewResult === 'CONFIRMED_VIOLATION' ? 'danger' : 'ok'"
                >
                  {{ detail.reviewResult === 'CONFIRMED_VIOLATION' ? '确认违规' : '误判放行' }}
                </span>
              </div>
              <div class="review-item">
                <span class="review-label">类型</span>
                <span>{{ detail.violationTypeLabel || '—' }}</span>
              </div>
              <div class="review-item">
                <span class="review-label">复检人</span>
                <span>{{ detail.reviewer || '—' }}</span>
              </div>
              <div class="review-item">
                <span class="review-label">时间</span>
                <span class="num">{{ formatTime(detail.reviewTime) }}</span>
              </div>
              <div class="review-item span-2">
                <span class="review-label">备注</span>
                <span>{{ detail.reviewComment || '—' }}</span>
              </div>
            </div>
          </section>

          <!-- 处理轨迹 -->
          <section class="block">
            <div class="block-title">处理轨迹</div>
            <el-timeline v-if="logs.length" class="timeline">
              <el-timeline-item
                v-for="(l, i) in logs"
                :key="i"
                :type="l.level === 'ERROR' ? 'danger' : l.level === 'WARN' ? 'warning' : 'primary'"
                :timestamp="formatTime(l.createdAt)"
                size="normal"
              >
                <span class="log-stage">{{ stageName(l.stage) }}</span>
                <span class="log-msg">{{ l.message }}</span>
              </el-timeline-item>
            </el-timeline>
            <div v-else class="dim tiny">暂无处理日志</div>
          </section>

          <!-- AI 调用详情：完整提示词与模型原始回复 -->
          <section v-if="aiInvocations.length" class="block">
            <div class="block-head">
              <div class="block-title">AI 调用详情</div>
              <span class="tiny dim">完整提示词与原始回复，共 {{ aiInvocations.length }} 次</span>
            </div>
            <el-collapse class="ai-collapse">
              <el-collapse-item v-for="inv in aiInvocations" :key="inv.id" :name="inv.id">
                <template #title>
                  <div class="ai-inv-title">
                    <span class="chip" :class="inv.stage === 'MINING' || inv.stage === 'EMBED_QUERY' ? 'info' : 'brand'">
                      {{ stageLabel(inv.stage) }}
                    </span>
                    <span class="mono ai-inv-model">{{ inv.providerLabel }} · {{ inv.model }}</span>
                    <span v-if="!inv.success" class="chip danger">失败</span>
                    <span v-else-if="inv.degraded" class="chip warn">降级</span>
                    <span v-else class="chip ok">成功</span>
                    <span class="spacer"></span>
                    <span class="num tiny dim">{{ inv.latencyMs }}ms</span>
                  </div>
                </template>

                <div class="ai-meta tiny dim">
                  <div>端点：<span class="mono">{{ inv.endpoint || '—' }}</span></div>
                  <div>
                    <span class="num">{{ formatTime(inv.createdAt) }}</span>
                    · 请求 {{ inv.requestChars }} 字符 · 回复 {{ inv.responseChars }} 字符
                  </div>
                </div>

                <div v-for="(m, i) in messagesOf(inv)" :key="i" class="ai-part">
                  <div class="ai-part-head">
                    <span class="ai-role" :class="m.role">
                      {{ m.role === 'system' ? '系统提示词' : '用户提示词' }}
                    </span>
                    <span class="tiny dim">{{ (m.content || '').length }} 字符</span>
                  </div>
                  <pre class="ai-raw">{{ m.content }}</pre>
                </div>

                <div v-if="!inv.requestJson" class="tiny dim">（请求体未留痕）</div>

                <div class="ai-part">
                  <div class="ai-part-head">
                    <span class="ai-role reply">模型回复</span>
                    <span class="tiny dim">{{ inv.responseChars }} 字符</span>
                  </div>
                  <pre v-if="inv.responseJson" class="ai-raw reply">{{ prettyJson(inv.responseJson) }}</pre>
                  <pre v-else class="ai-raw error">{{ inv.errorMessage || '（无回复）' }}</pre>
                </div>
              </el-collapse-item>
            </el-collapse>
          </section>
        </template>
      </div>
    </el-drawer>

    <!-- 上传录音：文件 + 元数据（坐席 / 团队 / 通话时间 / 方向 / 业务线） -->
    <el-dialog
      v-model="uploadVisible"
      title="上传录音"
      width="540px"
      :close-on-click-modal="!uploading"
      :close-on-press-escape="!uploading"
      :show-close="!uploading"
      @closed="resetUploadForm"
    >
      <el-form :model="uploadForm" label-width="76px" class="upload-form">
        <el-form-item label="音频文件" required>
          <el-upload
            ref="uploadRef"
            class="uploader"
            drag
            multiple
            :auto-upload="false"
            :limit="20"
            :show-file-list="true"
            accept="audio/*,.mp3,.wav,.m4a,.mp4,.webm,.ogg,.flac,.aac,.amr,.caf,.opus,.mov"
            :on-change="onUploadFileChange"
            :on-remove="onUploadFileRemove"
            :on-exceed="onUploadExceed"
          >
            <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
            <div class="el-upload__text">拖拽音频到此处，或 <em>点击选择</em></div>
            <template #tip>
              <div class="el-upload__tip">
                支持多选（最多 20 个），单文件 ≤ 25MB；按选择顺序逐个上传，元数据应用到本批全部文件
              </div>
            </template>
          </el-upload>
        </el-form-item>
        <el-form-item label="坐席">
          <el-input
            v-model="uploadForm.agentName"
            placeholder="如 张三 / 工号 A1024"
            clearable
            maxlength="64"
          />
        </el-form-item>
        <el-form-item label="团队">
          <el-input
            v-model="uploadForm.teamName"
            placeholder="如 客服一组"
            clearable
            maxlength="128"
          />
        </el-form-item>
        <el-form-item label="通话时间">
          <el-date-picker
            v-model="uploadForm.callTime"
            type="datetime"
            placeholder="选择通话时间"
            format="YYYY-MM-DD HH:mm:ss"
            value-format="YYYY-MM-DDTHH:mm:ss"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="方向">
          <el-select v-model="uploadForm.direction" placeholder="未选择按未知处理" clearable style="width: 100%">
            <el-option
              v-for="opt in DIRECTION_OPTIONS"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="业务线">
          <el-input
            v-model="uploadForm.businessLine"
            placeholder="如 电商 / 金融分期"
            clearable
            maxlength="64"
          />
        </el-form-item>
      </el-form>

      <!-- 批量上传队列：逐文件进度 / 状态 / 汇总 -->
      <div v-if="uploadQueue.length" class="upload-queue">
        <div class="uq-head">
          <span class="tiny dim">
            共 {{ uploadQueue.length }} 个文件 · 已完成 {{ uploadDoneCount }} / {{ uploadQueue.length }}
          </span>
          <span v-if="uploading" class="tiny dim">总进度 {{ uploadOverallPercent }}%</span>
        </div>
        <div v-for="item in uploadQueue" :key="item.uid" class="uq-row">
          <div class="uq-line">
            <span class="uq-name ellipsis" :title="item.name">{{ item.name }}</span>
            <span class="chip" :class="uploadStatusChip(item)">{{ uploadStatusLabel(item) }}</span>
            <span class="uq-size tiny dim">{{ formatSize(item.size) }}</span>
            <span class="uq-pct num">{{ item.progress }}%</span>
          </div>
          <el-progress
            :percentage="item.progress"
            :status="uploadProgressStatus(item)"
            :stroke-width="6"
            :show-text="false"
          />
          <div v-if="item.error" class="uq-error tiny">{{ item.error }}</div>
        </div>
      </div>

      <div v-if="uploadSummary && !uploading" class="upload-summary tiny">
        本批完成：成功 {{ uploadSummary.success }} · 失败 {{ uploadSummary.error }} ·
        取消 {{ uploadSummary.canceled }}
      </div>

      <div class="upload-tip tiny dim">
        元数据均为选填，后续可在列表与报表中按坐席 / 团队 / 通话时间 / 方向筛选；上传成功后表单自动清空。
      </div>
      <template #footer>
        <el-button :disabled="uploading" @click="uploadVisible = false">取消</el-button>
        <el-button v-if="uploading" @click="cancelRemaining">取消剩余</el-button>
        <el-button type="primary" :loading="uploading" @click="submitUpload">
          {{ uploading ? `上传中 ${uploadDoneCount} / ${uploadQueue.length}` : '开始上传' }}
        </el-button>
      </template>
    </el-dialog>

    <!-- 指派复核人（ADMIN） -->
    <AssignDialog v-model="assignVisible" :recording="assignTarget" @assigned="onAssigned" />
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Search, Refresh, UploadFilled, Close } from '@element-plus/icons-vue'
import api from '../api'
import auth from '../auth'
import AssignDialog from '../components/AssignDialog.vue'
import AudioPlayer from '../components/AudioPlayer.vue'
import PriorityTag from '../components/PriorityTag.vue'
import StatusTag from '../components/StatusTag.vue'
import TranscriptViewer from '../components/TranscriptViewer.vue'
import SegmentTranscript from '../components/SegmentTranscript.vue'
import { formatClock } from '../utils/segments'
import {
  UPLOAD_STATUS,
  UPLOAD_STATUS_LABEL,
  markRemainingCanceled,
  overallProgress,
  progressFromEvent,
  summarizeQueue,
  updateQueueItem
} from '../utils/uploadQueue.js'
import {
  DIRECTION_META,
  DIRECTION_OPTIONS,
  directionLabel,
  formatDuration,
  formatSize,
  formatTime,
  formatTimeShort,
  isFinalReviewStatus,
  isSampled,
  isStale,
  PRIORITY_OPTIONS,
  reprocessInfo,
  reviewProgress,
  PROCESSING_STATUSES
} from '../constants'

const route = useRoute()
const router = useRouter()

const rows = ref([])
const total = ref(0)
const page = ref(0)
const pageSize = 10
const loading = ref(false)
const statusFilter = ref('')
const keyword = ref('')
const priorityFilter = ref('')
const assignedFilter = ref('')
const overview = ref(null)

/* ---------- 列表列宽自适应 ----------
 * 录音列表共 9 列，内容区窄于阈值时收起「优先级」列（该信息仍可在详情抽屉查看），
 * 让表格在 1280/1024 笔记本宽度下尽量不横向滚动，避免固定操作列压住相邻列文字。
 * 用 ResizeObserver 而不是窗口断点：侧栏折叠/展开会改变内容宽度但不改变窗口宽度。 */
const tableWrap = ref(null)
const compactColumns = ref(false)
const narrowColumns = ref(false)
/* 表宽阈值：<1024 收起「优先级」，<900 再收起「指派」（两者都能在详情抽屉查看） */
const COMPACT_TABLE_WIDTH = 1024
const NARROW_TABLE_WIDTH = 900
let tableObserver = null

function observeTableWidth() {
  if (typeof ResizeObserver === 'undefined' || !tableWrap.value) return
  tableObserver = new ResizeObserver(([entry]) => {
    const width = entry?.contentRect?.width || 0
    if (width <= 0) return
    compactColumns.value = width < COMPACT_TABLE_WIDTH
    narrowColumns.value = width < NARROW_TABLE_WIDTH
  })
  tableObserver.observe(tableWrap.value)
}

// 元数据筛选（与后端 GET /recordings 的 agentName/teamName/direction/from/to 参数一一对应）
const callDateRange = ref([])
const agentFilter = ref('')
const teamFilter = ref('')
const directionFilter = ref('')

// 上传对话框：文件队列 + 元数据（Batch 4b 起支持多选、顺序上传、逐文件进度）
const MAX_UPLOAD_BYTES = 25 * 1024 * 1024
const uploadVisible = ref(false)
const uploading = ref(false)
const uploadRef = ref(null)
const uploadQueue = ref([])
const uploadSummary = ref(null)
const uploadForm = ref(emptyUploadForm())
let cancelRequested = false

function emptyUploadForm() {
  return { agentName: '', teamName: '', callTime: '', direction: '', businessLine: '' }
}

const detailVisible = ref(false)
const detailLoading = ref(false)
const detail = ref(null)
const logs = ref([])
const aiInvocations = ref([])

// 角色与工作流
const canWrite = computed(() => auth.hasRole('ADMIN', 'REVIEWER'))
const isAdmin = computed(() => auth.hasRole('ADMIN'))
const detailProgress = computed(() => reviewProgress(detail.value))
const assignVisible = ref(false)
const assignTarget = ref(null)

/** 未认领的待复检录音可被 ADMIN / REVIEWER 认领 */
function canClaimRow(row) {
  return canWrite.value && row?.status === 'NEEDS_REVIEW' && !row.assignedTo
}

const FILTERS = [
  { key: '', label: '全部', countKey: 'ALL' },
  { key: 'PROCESSING', label: '处理中', countKey: 'PROCESSING' },
  { key: 'NEEDS_REVIEW', label: '待复检', countKey: 'NEEDS_REVIEW' },
  { key: 'COMPLIANT', label: '自动通过', countKey: 'COMPLIANT' },
  { key: 'VIOLATION_CONFIRMED', label: '确认违规', countKey: 'VIOLATION_CONFIRMED' },
  { key: 'FALSE_POSITIVE', label: '误判放行', countKey: 'FALSE_POSITIVE' },
  { key: 'FAILED', label: '失败', countKey: 'FAILED' }
]

const filters = computed(() =>
  FILTERS.map((f) => {
    const counts = overview.value?.statusCounts
    let count = null
    if (counts) {
      if (f.countKey === 'ALL') count = overview.value?.totalUploads ?? 0
      else if (f.countKey === 'PROCESSING')
        count = PROCESSING_STATUSES.reduce((s, k) => s + (counts[k] || 0), 0)
      else count = counts[f.countKey] ?? 0
    }
    return { ...f, count }
  })
)

/* ---------- 元数据筛选 ---------- */

/** 日期参数统一转为 yyyy-MM-dd（快捷选项与手选可能给 Date 或字符串） */
function toDateParam(value) {
  if (!value) return undefined
  const d = value instanceof Date ? value : new Date(String(value).replace(/\//g, '-'))
  if (Number.isNaN(d.getTime())) return typeof value === 'string' ? value : undefined
  const pad = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

function dayOffset(days) {
  const d = new Date()
  d.setHours(0, 0, 0, 0)
  d.setDate(d.getDate() + days)
  return d
}

const dateShortcuts = [
  { text: '最近 7 天', value: () => [dayOffset(-6), dayOffset(0)] },
  { text: '最近 30 天', value: () => [dayOffset(-29), dayOffset(0)] },
  { text: '最近 90 天', value: () => [dayOffset(-89), dayOffset(0)] }
]

const metaParams = computed(() => ({
  agentName: agentFilter.value.trim() || undefined,
  teamName: teamFilter.value.trim() || undefined,
  direction: directionFilter.value || undefined,
  from: toDateParam(callDateRange.value?.[0]),
  to: toDateParam(callDateRange.value?.[1])
}))

const hasMetaFilters = computed(
  () =>
    !!(
      agentFilter.value.trim() ||
      teamFilter.value.trim() ||
      directionFilter.value ||
      callDateRange.value?.length
    )
)

function resetMetaFilters() {
  agentFilter.value = ''
  teamFilter.value = ''
  directionFilter.value = ''
  callDateRange.value = []
  reload()
}

/** 方向胶囊的配色 */
function directionClass(direction) {
  return DIRECTION_META[direction]?.type || 'info'
}

const dfaHits = computed(() => {
  try {
    return JSON.parse(detail.value?.dfaHitsJson || '[]')
  } catch {
    return []
  }
})
const aiResult = computed(() => {
  try {
    return JSON.parse(detail.value?.aiResultJson || 'null')
  } catch {
    return null
  }
})
/** AI 判定违规时的类型标签（NONE/无 不展示） */
const aiTypeLabel = computed(() => {
  const r = aiResult.value
  if (!r || r.violationType === 'NONE') return ''
  return r.violationTypeLabel || ''
})
const segments = computed(() => {
  try {
    return JSON.parse(detail.value?.segmentsJson || '[]')
  } catch {
    return []
  }
})
const audioUrl = computed(() => (detail.value?.filePath ? `/uploads/${detail.value.filePath}` : ''))

// 音画联动 + 分声道试听
const playerRef = ref(null)
const currentTime = ref(0)
const follow = ref(true)
const transcriptMode = ref('segment')
const audioChannel = ref('mix')
const modeInitialized = ref(false)

const channelFiles = computed(() => {
  try {
    return JSON.parse(detail.value?.channelFilesJson || 'null')
  } catch {
    return null
  }
})
const dialogueAvailable = computed(() => segments.value.some((s) => s?.channel))
const channelOptions = computed(() => {
  const files = channelFiles.value || {}
  const leftName = segments.value.find((s) => s?.channel === 'L')?.speaker || '左声道'
  const rightName = segments.value.find((s) => s?.channel === 'R')?.speaker || '右声道'
  return [
    { key: 'mix', label: '混合', badge: '' },
    { key: 'L', label: files.L ? leftName : '左声道', badge: 'L' },
    { key: 'R', label: files.R ? rightName : '右声道', badge: 'R' }
  ]
})
const audioSrc = computed(() => {
  const files = channelFiles.value
  if (files && audioChannel.value !== 'mix' && files[audioChannel.value]) {
    return `/uploads/${files[audioChannel.value]}`
  }
  return audioUrl.value
})

function switchChannel(key) {
  if (audioChannel.value === key) return
  audioChannel.value = key
  currentTime.value = 0
}

function onTimeUpdate(time) {
  currentTime.value = Number(time) || 0
}

/** 点击句子 → 跳转到对应音频时间点并播放 */
function seekTo(time) {
  if (time == null) return
  currentTime.value = time
  if (!audioUrl.value) {
    ElMessage.info(`该录音无音频文件，句子起始时间 ${formatClock(time)}`)
    return
  }
  playerRef.value?.seekTo(time)
}

const STAGE_NAMES = {
  PIPELINE: '流水线',
  TRANSCRIBE: 'Whisper 转写',
  DFA: 'DFA 初筛',
  AI: 'AI 复筛',
  REVIEW: '人工复检',
  MINING: '词典挖掘',
  RAG: '知识库检索'
}
const stageName = (s) => STAGE_NAMES[s] || s

/* ---------- AI 调用留痕 ---------- */
const stageLabel = (s) => (s === 'MINING' ? '敏感词挖掘'
  : s === 'EMBED_QUERY' ? '检索向量化'
  : s === 'EMBED_INDEX' ? '索引向量化'
  : '语义复筛')

/** 从留痕的请求体里取出 messages，供分栏展示 */
function messagesOf(inv) {
  try {
    return JSON.parse(inv.requestJson || '{}').messages || []
  } catch {
    return []
  }
}

/** 模型回复是 JSON 字符串，格式化后更易读；解析失败则原样展示 */
function prettyJson(raw) {
  try {
    return JSON.stringify(JSON.parse(raw), null, 2)
  } catch {
    return raw
  }
}

async function load() {
  loading.value = true
  try {
    const statuses =
      statusFilter.value === 'PROCESSING'
        ? PROCESSING_STATUSES
        : statusFilter.value
          ? [statusFilter.value]
          : undefined
    const data = await api.listRecordings({
      statuses,
      keyword: keyword.value || undefined,
      assignedTo: assignedFilter.value === 'mine' ? auth.username : undefined,
      priority: priorityFilter.value || undefined,
      ...metaParams.value,
      page: page.value,
      size: pageSize
    })
    rows.value = data.content || []
    total.value = data.totalElements || 0
  } finally {
    loading.value = false
  }
}

async function loadOverview() {
  try {
    overview.value = await api.overview()
  } catch {
    // 忽略
  }
}

function applyFilter(key) {
  statusFilter.value = key
  reload()
}

function reload() {
  page.value = 0
  load()
}

/* ---------- 上传录音（文件 + 元数据） ---------- */

function openUpload() {
  uploadVisible.value = true
}

function resetUploadForm() {
  uploadForm.value = emptyUploadForm()
  uploadQueue.value = []
  uploadSummary.value = null
  cancelRequested = false
  uploading.value = false
  uploadRef.value?.clearFiles?.()
}

/** 队列已完成数量（成功 / 失败 / 取消） */
const uploadDoneCount = computed(
  () =>
    uploadQueue.value.filter((item) =>
      [UPLOAD_STATUS.SUCCESS, UPLOAD_STATUS.ERROR, UPLOAD_STATUS.CANCELED].includes(item.status)
    ).length
)
const uploadOverallPercent = computed(() => overallProgress(uploadQueue.value))

function uploadStatusLabel(item) {
  return UPLOAD_STATUS_LABEL[item.status] || item.status
}
function uploadStatusChip(item) {
  if (item.status === UPLOAD_STATUS.SUCCESS) return 'ok'
  if (item.status === UPLOAD_STATUS.ERROR) return 'danger'
  if (item.status === UPLOAD_STATUS.UPLOADING) return 'warn'
  return 'info'
}
function uploadProgressStatus(item) {
  if (item.status === UPLOAD_STATUS.SUCCESS) return 'success'
  if (item.status === UPLOAD_STATUS.ERROR) return 'exception'
  if (item.status === UPLOAD_STATUS.CANCELED) return 'warning'
  return undefined
}

/**
 * el-upload 文件列表变化：以 el-upload 的 uid 为稳定 key 同步队列，
 * 保留已有文件的上传状态与进度；超过 25MB 的文件直接标记失败并跳过上传。
 */
function syncUploadQueue(fileList) {
  const previous = new Map(uploadQueue.value.map((item) => [item.uid, item]))
  uploadQueue.value = (fileList || []).map((entry) => {
    const raw = entry.raw || entry
    const uid = String(entry.uid ?? `${raw?.name}-${raw?.size}`)
    const tooLarge = Number(raw?.size) > MAX_UPLOAD_BYTES
    const prev = previous.get(uid)
    if (prev) {
      if (tooLarge && prev.status === UPLOAD_STATUS.PENDING) {
        return { ...prev, status: UPLOAD_STATUS.ERROR, error: '超过 25MB（Whisper 单文件上限）' }
      }
      // 移除后重新选择同一文件：保留新 raw 引用
      return { ...prev, file: raw }
    }
    return {
      uid,
      file: raw,
      name: raw?.name || '未知文件',
      size: Number(raw?.size) || 0,
      progress: 0,
      status: tooLarge ? UPLOAD_STATUS.ERROR : UPLOAD_STATUS.PENDING,
      error: tooLarge ? '超过 25MB（Whisper 单文件上限）' : ''
    }
  })
}

function onUploadFileChange(_file, fileList) {
  syncUploadQueue(fileList)
}

function onUploadFileRemove(_file, fileList) {
  syncUploadQueue(fileList)
}

/** 超过 20 个文件：提示而不是清空已选队列 */
function onUploadExceed() {
  ElMessage.warning('一次最多上传 20 个文件，请分批上传')
}

/** 取消剩余：正在上传的当前文件会继续完成，未开始的标记为已取消 */
function cancelRemaining() {
  if (!uploading.value) return
  cancelRequested = true
  uploadQueue.value = markRemainingCanceled(uploadQueue.value)
  ElMessage.info('已取消剩余文件（当前文件仍会上传完成）')
}

async function submitUpload() {
  if (!uploadQueue.value.length) {
    ElMessage.warning('请选择要上传的音频文件')
    return
  }
  // 失败项（非超限）允许点击「开始上传」重试
  uploadQueue.value = uploadQueue.value.map((item) =>
    item.status === UPLOAD_STATUS.ERROR && item.size <= MAX_UPLOAD_BYTES
      ? { ...item, status: UPLOAD_STATUS.PENDING, progress: 0, error: '' }
      : item
  )
  const ready = uploadQueue.value.filter((item) => item.status === UPLOAD_STATUS.PENDING)
  if (!ready.length) {
    const hasOversized = uploadQueue.value.some(
      (item) => item.status === UPLOAD_STATUS.ERROR && item.size > MAX_UPLOAD_BYTES
    )
    ElMessage.warning(
      hasOversized
        ? '没有可上传的文件：请移除超过 25MB 的文件后重试'
        : '本批已全部处理完成，可关闭窗口或重新选择文件'
    )
    return
  }

  uploading.value = true
  uploadSummary.value = null
  cancelRequested = false
  const totalFiles = uploadQueue.value.length
  let success = 0
  let failed = 0

  try {
    for (const queued of ready) {
      if (cancelRequested) break
      const current = uploadQueue.value.find((item) => item.uid === queued.uid)
      if (!current || current.status !== UPLOAD_STATUS.PENDING) continue

      uploadQueue.value = updateQueueItem(uploadQueue.value, queued.uid, {
        status: UPLOAD_STATUS.UPLOADING,
        progress: 0,
        error: ''
      })
      try {
        const result = await api.upload(current.file, uploadForm.value, {
          onUploadProgress: (event) => {
            uploadQueue.value = updateQueueItem(uploadQueue.value, queued.uid, {
              progress: progressFromEvent(event)
            })
          }
        })
        uploadQueue.value = updateQueueItem(uploadQueue.value, queued.uid, {
          status: UPLOAD_STATUS.SUCCESS,
          progress: 100,
          resultId: result?.id
        })
        success += 1
      } catch (e) {
        uploadQueue.value = updateQueueItem(uploadQueue.value, queued.uid, {
          status: UPLOAD_STATUS.ERROR,
          error: e?.message || '上传失败'
        })
        failed += 1
      }
    }
  } finally {
    uploading.value = false
  }

  const summary = summarizeQueue(uploadQueue.value)
  uploadSummary.value = summary
  if (success > 0) {
    ElMessage.success(`本批成功上传 ${success} 个文件，已进入稽核流水线`)
    reload()
    loadOverview()
  }
  if (failed > 0) ElMessage.error(`${failed} 个文件上传失败，可在列表中重试或重新选择`)

  // 保持单文件旧体验：单个文件全部成功时自动关闭并清空
  if (totalFiles === 1 && summary.success === 1) {
    uploadVisible.value = false
    resetUploadForm()
  }
}

async function openDetail(row) {
  if (!row?.id) return
  detailVisible.value = true
  detailLoading.value = true
  detail.value = null
  logs.value = []
  aiInvocations.value = []
  currentTime.value = 0
  audioChannel.value = 'mix'
  transcriptMode.value = 'segment'
  try {
    const [d, l, inv] = await Promise.all([
      api.recordingDetail(row.id),
      api.recordingLogs(row.id),
      api.recordingAiInvocations(row.id)
    ])
    detail.value = d
    logs.value = l || []
    aiInvocations.value = inv || []
    // 双轨录音首次打开默认双栏对话
    if (dialogueAvailable.value && !modeInitialized.value) {
      transcriptMode.value = 'dialogue'
    }
    modeInitialized.value = true
  } finally {
    detailLoading.value = false
  }
}

/** 手动推进/重新处理：清空上一轮产物后重新进入流水线 */
async function reprocess(row) {
  const info = reprocessInfo(row)
  await api.reprocessRecording(row.id)
  ElMessage.success(`${info?.label || '处理'}已触发，正在重新执行流水线`)
  load()
  loadOverview()
  if (detail.value?.id === row.id) openDetail(row)
}

async function remove(row) {
  await api.deleteRecording(row.id)
  ElMessage.success('已删除')
  if (detail.value?.id === row.id) detailVisible.value = false
  load()
  loadOverview()
}

/* ---------- 复核工作流：认领 / 指派 / 重开 ---------- */

/** 认领：仅未指派的待复检录音；已被他人认领时后端返回 409 */
async function claim(row) {
  if (!row?.id) return
  try {
    const saved = await api.claimRecording(row.id)
    const assignedTo = saved?.assignedTo || auth.username
    const patch = saved && typeof saved === 'object' ? saved : {}
    rows.value = rows.value.map((r) =>
      r.id === row.id ? { ...r, ...patch, assignedTo } : r
    )
    if (detail.value?.id === row.id) {
      detail.value = { ...detail.value, ...patch, assignedTo }
    }
    ElMessage.success('已认领，可在人工复检页处理')
  } catch {
    // 409 等由请求层统一提示
  }
}

function openAssign(row) {
  if (!row?.id) return
  assignTarget.value = row
  assignVisible.value = true
}

function onAssigned({ username }) {
  const id = assignTarget.value?.id
  if (!id) return
  rows.value = rows.value.map((r) => (r.id === id ? { ...r, assignedTo: username } : r))
  if (detail.value?.id === id) detail.value = { ...detail.value, assignedTo: username }
}

/** 重开终态录音（ADMIN）：清空最终结论，回到待人工复检 */
async function reopen(row) {
  if (!row?.id) return
  try {
    await api.reopenRecording(row.id)
    ElMessage.success('已重开为待人工复检')
    if (detail.value?.id === row.id) detailVisible.value = false
    await load()
    loadOverview()
  } catch {
    // 请求层已统一提示
  }
}

let timer = null
const hasProcessing = () => rows.value.some((r) => PROCESSING_STATUSES.includes(r.status))

onMounted(async () => {
  observeTableWidth()
  // 支持从工作台/其他页面带条件跳转
  const qs = String(route.query.statuses || '')
  if (qs) statusFilter.value = qs.split(',')[0] === 'FAILED' ? 'FAILED' : qs.split(',')[0]
  loadOverview()
  await load()
  if (route.query.id) {
    openDetail({ id: Number(route.query.id) })
  }
  timer = setInterval(() => {
    if (hasProcessing()) {
      load()
      loadOverview()
    }
  }, 3000)
})
onUnmounted(() => {
  clearInterval(timer)
  tableObserver?.disconnect()
  tableObserver = null
})
</script>

<style scoped>
/* 工具栏 */
.toolbar {
  display: flex;
  flex-direction: column;
  align-items: stretch;
  gap: 10px;
  padding: 16px 18px 12px;
}
.filters {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.filter-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 32px;
  padding: 0 13px;
  border-radius: 999px;
  border: 1px solid var(--border);
  background: #fff;
  color: var(--el-text-color-regular);
  font-size: 12.5px;
  font-family: inherit;
  cursor: pointer;
  transition: all 0.16s ease;
}
.filter-chip:hover {
  border-color: var(--brand-300);
  color: var(--brand-600);
}
.filter-chip.on {
  background: var(--brand-500);
  border-color: var(--brand-500);
  color: #fff;
  box-shadow: 0 4px 12px rgba(59, 110, 246, 0.24);
}
.filter-count {
  font-size: 11px;
  opacity: 0.72;
  font-variant-numeric: tabular-nums;
}
.actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
  flex-wrap: wrap;
}
.actions :deep(.el-input__wrapper),
.actions :deep(.el-select__wrapper) {
  min-height: 32px;
}
.search {
  width: 252px;
}
.priority-select {
  width: 132px;
}
.filter-select {
  width: 118px;
}
.read-only-chip {
  flex: none;
}
/* 元数据筛选行 */
.meta-filters {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  padding: 0 18px 12px;
}
.meta-filters :deep(.el-input__wrapper),
.meta-filters :deep(.el-select__wrapper) {
  min-height: 32px;
}
.date-range {
  width: 300px;
}
.meta-input {
  width: 136px;
}
.direction-select {
  width: 128px;
}
.meta-clear {
  margin-left: 2px;
  font-size: 12.5px;
}
.hint {
  padding: 2px 18px 16px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.75;
}
.hint-line {
  margin: 0;
  max-width: 980px;
}
.hint-line + .hint-line {
  margin-top: 3px;
}
.accent {
  color: #c92c2c;
  font-weight: 600;
}

/* 表格 */
:deep(.row-clickable) {
  cursor: pointer;
}
/* 80–104px 的窄列（DFA 命中 / AI 结论 / 优先级 / 指派）用 12px 内边距，
   避免全局 18px 把内容挤到折行；首列与其余列仍保持 18px 基线。 */
:deep(.col-tight .cell) {
  padding-left: 12px;
  padding-right: 12px;
}
.cell-ellipsis {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
/* 坐席 / 团队：双行单元格 */
.agent-cell {
  display: flex;
  flex-direction: column;
  gap: 1px;
  min-width: 0;
  line-height: 1.5;
}
.agent-line {
  min-width: 0;
  font-size: 12.5px;
  color: var(--ink-700);
}
.agent-line.sub {
  font-size: 11.5px;
  color: var(--el-text-color-placeholder);
}
/* 通话时间 + 方向：两行排布，避免挤压 */
.call-cell {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 3px;
  min-width: 0;
  font-size: 12.5px;
}
.call-time {
  color: var(--ink-700);
  white-space: nowrap;
}
.dir-chip {
  height: 18px;
  padding: 0 7px;
  font-size: 11px;
}
.hit-chip {
  height: 20px;
  padding: 0 7px;
  gap: 3px;
  font-size: 11.5px;
}
.prio-cell {
  height: 20px;
  padding: 0 7px;
  gap: 4px;
  font-size: 11.5px;
}
.assignee-chip {
  max-width: 100%;
  height: 20px;
  padding: 0 8px;
  font-size: 11.5px;
}
.status-cell {
  display: inline-flex;
  flex-direction: column;
  align-items: center;
  gap: 3px;
  min-width: 0;
  max-width: 100%;
}
.status-line {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.status-line :deep(.status-tag) {
  height: 22px;
  padding: 0 8px;
  font-size: 11.5px;
}
.stale-icon {
  color: var(--warn);
  flex: none;
}
.reviewer-line {
  max-width: 100%;
  font-size: 11px;
  line-height: 1.4;
  color: var(--el-text-color-placeholder);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
/* 行内操作：紧凑链接 + 更多 */
.row-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 7px;
  min-width: 0;
}
.op-link {
  height: 22px;
  padding: 0;
  font-size: 12.5px;
}
.op-link + .op-link {
  margin-left: 0;
}
.more-btn {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  height: 22px;
  padding: 0;
  border: none;
  background: none;
  color: var(--brand-600);
  font-family: inherit;
  font-size: 12.5px;
  font-weight: 500;
  cursor: pointer;
  white-space: nowrap;
}
.more-btn:hover {
  color: var(--brand-700);
}
.more-list {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 2px;
}
.more-item {
  width: 100%;
  height: 30px;
  padding: 0 10px;
  border: none;
  border-radius: 6px;
  background: none;
  color: var(--el-text-color-regular);
  font-family: inherit;
  font-size: 12.5px;
  text-align: left;
  cursor: pointer;
  transition: background 0.15s ease, color 0.15s ease;
}
.more-item:hover {
  background: var(--ink-50);
  color: var(--brand-600);
}
.more-item.warn,
.more-item.tone-warning {
  color: #b06a06;
}
.more-item.tone-primary {
  color: var(--brand-600);
}
.more-item.danger {
  color: var(--danger);
}
.file-cell {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}
.file-icon {
  position: relative;
  width: 32px;
  height: 32px;
  flex: none;
  border-radius: 9px;
  display: grid;
  place-items: center;
  background: var(--brand-50);
  color: var(--brand-600);
}
.file-icon.sampled {
  box-shadow: 0 0 0 1.5px rgba(245, 158, 11, 0.45);
}
.sample-dot {
  position: absolute;
  top: -3px;
  right: -3px;
  box-sizing: border-box;
  width: 11px;
  height: 11px;
  border-radius: 50%;
  background: #f59e0b;
  border: 2px solid #fff;
}
.file-icon.lg {
  width: 38px;
  height: 38px;
  border-radius: 11px;
}
.file-meta {
  min-width: 0;
}
.file-name {
  font-weight: 500;
  color: var(--ink-700);
  line-height: 1.45;
}
.file-sub {
  font-size: 11.5px;
  color: var(--el-text-color-placeholder);
  margin-top: 3px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.sep {
  margin: 0 4px;
}
.ellipsis {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.empty-icon {
  color: var(--ink-300);
}
.empty-title {
  margin: 8px 0 4px;
  font-weight: 600;
  color: var(--ink-600);
}

.pager {
  display: flex;
  justify-content: flex-end;
  padding: 14px 18px 16px;
}

/* 抽屉 */
.detail {
  display: flex;
  flex-direction: column;
  gap: 16px;
  min-height: 200px;
}
.detail-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}
.detail-title {
  display: flex;
  gap: 11px;
  min-width: 0;
}
.detail-name {
  font-size: 15px;
  font-weight: 600;
  color: var(--ink-800);
  max-width: 420px;
}
.detail-chips {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 7px;
  flex-wrap: wrap;
}
.detail-head-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: none;
}
/* 手机抽屉：标题 + 操作按钮同行会把文件名压成 0 宽、chips 竖排，
   改成标题在上、操作按钮在下 */
@media (max-width: 600px) {
  .detail-head {
    flex-wrap: wrap;
    gap: 8px;
  }
  .detail-head-actions {
    width: 100%;
    justify-content: flex-end;
  }
}
.min-w-0 {
  min-width: 0;
}

/* 播放器行 + 分声道试听 */
.player-row {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
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

.block {
  border: 1px solid var(--border);
  border-radius: var(--r-lg);
  padding: 14px 16px;
  background: #fff;
}
.block-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 12px;
}
.block-title {
  font-size: 13.5px;
  font-weight: 600;
  color: var(--ink-700);
  margin-bottom: 12px;
}

/* 逐句 / 全文切换 */
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

/* AI 结论 */
.verdict {
  border-radius: var(--r-md);
  padding: 12px 14px;
  border: 1px solid var(--border);
  background: #fbfcfe;
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
.conf {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.conf b {
  color: var(--ink-700);
  margin-left: 3px;
}
.conf-track {
  height: 6px;
  border-radius: 999px;
  background: rgba(15, 23, 42, 0.07);
  margin: 10px 0;
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
  border-top: none;
}
.ai-collapse :deep(.el-collapse-item__header) {
  height: auto;
  min-height: 34px;
  padding: 4px 0;
  line-height: 1.5;
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
  max-height: 280px;
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

/* 复检 */
.review-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px 16px;
  font-size: 12.5px;
}
.review-item {
  display: flex;
  gap: 10px;
  align-items: center;
}
.review-item.span-2 {
  grid-column: span 2;
  align-items: flex-start;
}
.review-label {
  color: var(--el-text-color-placeholder);
  flex: none;
  min-width: 42px;
}

/* 轨迹 */
.timeline {
  padding-left: 2px;
}
.log-stage {
  display: inline-block;
  font-size: 11.5px;
  font-weight: 600;
  color: var(--brand-600);
  background: var(--brand-50);
  border-radius: 5px;
  padding: 1px 7px;
  margin-right: 8px;
}
.log-msg {
  font-size: 12.5px;
  color: var(--el-text-color-regular);
  line-height: 1.65;
}

/* 上传对话框 */
.upload-form {
  padding-right: 6px;
}
.uploader {
  width: 100%;
}
.uploader :deep(.el-upload),
.uploader :deep(.el-upload-dragger) {
  width: 100%;
}
.uploader :deep(.el-upload-dragger) {
  padding: 18px 12px;
}
.upload-tip {
  margin-top: -2px;
  padding-left: 82px;
  line-height: 1.6;
}
/* 手机：76px 标签列会把拖拽区压到 205px，改成一列（标签在上） */
@media (max-width: 520px) {
  .upload-form :deep(.el-form-item) {
    display: block;
  }
  .upload-form :deep(.el-form-item__label) {
    width: auto !important;
    justify-content: flex-start;
    padding: 0 0 4px;
    line-height: 1.4;
  }
  .upload-form :deep(.el-form-item__content) {
    margin-left: 0 !important;
  }
  .upload-tip {
    padding-left: 0;
  }
}

/* 批量上传队列 */
.upload-queue {
  max-height: 220px;
  overflow-y: auto;
  margin-top: 10px;
  padding: 10px 12px;
  border: 1px solid var(--border);
  border-radius: var(--r-md);
  background: var(--ink-50);
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.uq-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  line-height: 1.4;
}
.uq-row {
  display: flex;
  flex-direction: column;
  gap: 5px;
}
.uq-line {
  display: flex;
  align-items: center;
  gap: 7px;
  min-width: 0;
}
.uq-name {
  flex: 1;
  min-width: 0;
  font-size: 12.5px;
  color: var(--ink-700);
}
.uq-size {
  flex: none;
}
.uq-pct {
  flex: none;
  width: 40px;
  text-align: right;
  font-size: 11.5px;
  color: var(--el-text-color-secondary);
}
.uq-error {
  color: var(--danger);
  line-height: 1.5;
}
.upload-summary {
  margin-top: 10px;
  padding: 8px 12px;
  border-radius: var(--r-md);
  background: var(--brand-50);
  color: var(--brand-700);
}
</style>
