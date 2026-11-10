<template>
  <div class="page stack">
    <!-- 工具栏 -->
    <section class="panel">
      <div class="toolbar">
        <div class="filters">
          <SegmentedControl
            v-model="statusFilter"
            :options="statusOptions"
            :aria-label="t('recordings.filters.group')"
            @change="reload"
          />
        </div>

        <div class="actions">
          <span v-if="!canWrite" class="chip info read-only-chip">{{
            t('recordings.readOnly')
          }}</span>
          <SegmentedControl
            v-model="assignedFilter"
            :options="assignedOptions"
            :aria-label="t('recordings.assigned.placeholder')"
            @change="reload"
          />
          <SegmentedControl
            v-model="priorityFilter"
            :options="priorityOptions"
            :aria-label="t('recordings.priority.placeholder')"
            @change="reload"
          />
          <el-input
            v-model="keyword"
            :placeholder="t('recordings.search.placeholder')"
            clearable
            class="search"
            @keyup.enter="reload"
            @clear="reload"
          >
            <template #prefix>
              <el-icon><Search /></el-icon>
            </template>
          </el-input>
          <el-button
            :icon="Refresh"
            circle
            :aria-label="t('common.refresh')"
            :title="t('common.refresh')"
            @click="reload"
          />
          <el-button v-if="canWrite" type="primary" :icon="UploadFilled" @click="openUpload">{{
            t('recordings.upload.action')
          }}</el-button>
        </div>
      </div>

      <!-- 元数据筛选：通话日期范围 / 坐席 / 团队 / 方向（与后端 query 参数一致） -->
      <div class="meta-filters">
        <el-date-picker
          v-model="callDateRange"
          type="daterange"
          value-format="YYYY-MM-DD"
          unlink-panels
          :range-separator="t('recordings.meta.rangeSeparator')"
          :start-placeholder="t('recordings.field.callStartDate')"
          :end-placeholder="t('recordings.field.callEndDate')"
          :shortcuts="dateShortcuts"
          class="date-range"
          @change="reload"
        />
        <el-input
          v-model="agentFilter"
          :placeholder="t('recordings.field.agent')"
          clearable
          class="meta-input"
          @keyup.enter="reload"
          @clear="reload"
        />
        <el-input
          v-model="teamFilter"
          :placeholder="t('recordings.field.team')"
          clearable
          class="meta-input"
          @keyup.enter="reload"
          @clear="reload"
        />
        <SegmentedControl
          v-model="directionFilter"
          :options="directionOptions"
          :aria-label="t('recordings.meta.allDirections')"
          @change="reload"
        />
        <el-button
          v-if="hasMetaFilters"
          class="meta-clear"
          link
          type="primary"
          @click="resetMetaFilters"
          >{{ t('recordings.meta.clear') }}</el-button
        >
      </div>
      <div class="hint">
        <template v-if="canWrite">
          <p class="hint-line">
            {{ t('recordings.hint.pipelinePrefix')
            }}<span class="accent">{{ t('recordings.hint.pipelineAccent') }}</span
            >{{ t('recordings.hint.pipelineSuffix', { maxMb: MAX_UPLOAD_MB }) }}
          </p>
          <p class="hint-line">
            {{ t('recordings.hint.stuckPrefix')
            }}<span class="accent">{{ t('recordings.hint.stuckAccent') }}</span
            >{{ t('recordings.hint.stuckSuffix') }}
          </p>
        </template>
        <p v-else class="hint-line">
          {{ t('recordings.hint.readOnly') }}
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
        <el-table-column :label="t('recordings.table.recording')" min-width="188">
          <template #default="{ row }">
            <div class="file-cell">
              <div
                class="file-icon"
                :class="{ sampled: isSampled(row) }"
                :title="isSampled(row) ? t('recordings.sampled.tooltip') : undefined"
              >
                <el-icon><Microphone /></el-icon>
                <span v-if="isSampled(row)" class="sample-dot" aria-hidden="true"></span>
              </div>
              <div class="file-meta">
                <div class="file-name ellipsis" :title="row.fileName">{{ row.fileName }}</div>
                <div class="file-sub">
                  <span class="mono">#{{ row.id }}</span>
                  <span class="sep">·</span>
                  <span class="num">{{ formatTimeShort(row.uploadTime) }}</span>
                </div>
              </div>
            </div>
          </template>
        </el-table-column>

        <el-table-column :label="t('recordings.table.agentTeam')" width="100">
          <template #default="{ row }">
            <div class="agent-cell">
              <div class="agent-line">
                <span v-if="row.agentName" class="cell-ellipsis" :title="row.agentName">{{
                  row.agentName
                }}</span>
                <span v-else class="dim">—</span>
              </div>
              <div class="agent-line sub">
                <span v-if="row.teamName" class="cell-ellipsis" :title="row.teamName">{{
                  row.teamName
                }}</span>
                <span v-else class="dim">—</span>
              </div>
            </div>
          </template>
        </el-table-column>

        <el-table-column
          :label="t('recordings.table.direction')"
          width="88"
          align="center"
          class-name="col-tight"
          label-class-name="col-tight"
        >
          <template #default="{ row }">
            <span
              v-if="row.direction && DIRECTION_META[row.direction]"
              class="chip dir-chip"
              :class="DIRECTION_META[row.direction].type"
              >{{ DIRECTION_META[row.direction].label }}</span
            >
            <span v-else class="dim">—</span>
          </template>
        </el-table-column>

        <el-table-column :label="t('recordings.table.callTime')" width="170">
          <template #default="{ row }">
            <span v-if="row.callTime" class="num call-time" :title="formatTime(row.callTime)">
              {{ formatTime(row.callTime) }}
            </span>
            <span v-else class="dim">—</span>
          </template>
        </el-table-column>

        <el-table-column
          :label="t('recordings.table.duration')"
          width="92"
          align="center"
          class-name="col-tight"
          label-class-name="col-tight"
        >
          <template #default="{ row }">
            <span v-if="row.durationSeconds != null" class="num call-duration">
              {{ formatDuration(row.durationSeconds) }}
            </span>
            <span v-else class="dim">—</span>
          </template>
        </el-table-column>

        <el-table-column
          :label="t('recordings.table.channels')"
          width="96"
          align="center"
          class-name="col-tight"
          label-class-name="col-tight"
        >
          <template #default="{ row }">
            <span v-if="channelKind(row.channelCount) === 'stereo'" class="chip brand">
              {{ t('recordings.table.channelsStereo') }}
            </span>
            <span v-else-if="channelKind(row.channelCount) === 'mono'" class="chip">
              {{ t('recordings.table.channelsMono') }}
            </span>
            <span v-else class="dim">—</span>
          </template>
        </el-table-column>

        <el-table-column
          :label="t('recordings.table.dfaHits')"
          width="108"
          align="center"
          class-name="col-tight"
          label-class-name="col-tight"
        >
          <template #default="{ row }">
            <span v-if="row.hitCount" class="chip danger hit-chip">
              <el-icon :size="10"><WarningFilled /></el-icon
              >{{ countLabel('recordings.table.hitCount', row.hitCount) }}
            </span>
            <span v-else class="dim">—</span>
          </template>
        </el-table-column>

        <el-table-column
          :label="t('recordings.table.aiVerdict')"
          width="136"
          align="center"
          class-name="col-tight"
          label-class-name="col-tight"
        >
          <template #default="{ row }">
            <span v-if="row.aiViolationTypeLabel" class="chip warn">{{
              row.aiViolationTypeLabel
            }}</span>
            <span v-else-if="row.aiViolation === true" class="chip danger">{{
              t('recordings.aiVerdict.violation')
            }}</span>
            <span v-else-if="row.aiViolation === false" class="chip ok">{{
              t('recordings.aiVerdict.compliant')
            }}</span>
            <span
              v-else-if="row.hitCount && PROCESSING_STATUSES.includes(row.status)"
              class="chip info"
              >{{ t('recordings.aiVerdict.screening') }}</span
            >
            <span v-else class="dim">—</span>
          </template>
        </el-table-column>

        <el-table-column
          v-if="!compactColumns"
          :label="t('recordings.table.priority')"
          width="104"
          align="center"
          class-name="col-tight"
          label-class-name="col-tight"
        >
          <template #default="{ row }">
            <PriorityTag :priority="row.priority" class="prio-cell" />
          </template>
        </el-table-column>

        <el-table-column
          v-if="!narrowColumns"
          :label="t('recordings.table.assignee')"
          width="104"
          align="center"
          class-name="col-tight"
          label-class-name="col-tight"
        >
          <template #default="{ row }">
            <span
              v-if="row.assignedTo"
              class="chip assignee-chip"
              :class="{ brand: row.assignedTo === auth.username }"
              :title="row.assignedTo"
              >{{
                row.assignedTo === auth.username ? t('recordings.table.me') : row.assignedTo
              }}</span
            >
            <span v-else class="dim">{{ t('recordings.table.unassigned') }}</span>
          </template>
        </el-table-column>

        <el-table-column :label="t('recordings.table.statusReviewer')" width="152" align="center">
          <template #default="{ row }">
            <div class="status-cell">
              <div class="status-line">
                <StatusTag :status="row.status" />
                <el-tooltip
                  v-if="isStale(row)"
                  :content="t('recordings.table.staleHint')"
                  placement="top"
                >
                  <el-icon class="stale-icon" :size="13"><WarningFilled /></el-icon>
                </el-tooltip>
              </div>
              <el-tooltip
                v-if="row.reviewer"
                :content="`${row.reviewResult === 'CONFIRMED_VIOLATION' ? STATUS_META.VIOLATION_CONFIRMED.label : STATUS_META.FALSE_POSITIVE.label} · ${formatTime(row.reviewTime)}`"
                placement="top"
              >
                <span class="reviewer-line">{{
                  t('recordings.table.reviewedBy', { name: row.reviewer })
                }}</span>
              </el-tooltip>
            </div>
          </template>
        </el-table-column>

        <el-table-column :label="t('common.actions')" width="216" align="right" fixed="right">
          <template #default="{ row }">
            <div class="row-actions">
              <el-button class="op-link" link type="primary" @click.stop="openDetail(row)">{{
                t('common.detail')
              }}</el-button>
              <el-button
                v-if="row.status === 'NEEDS_REVIEW'"
                class="op-link"
                link
                type="danger"
                @click.stop="$router.push(`/review?id=${row.id}`)"
                >{{ t('recordings.actions.goReview') }}</el-button
              >
              <el-popover
                v-if="canWrite"
                trigger="click"
                placement="bottom-end"
                :width="168"
                popper-class="rec-more-popper"
              >
                <template #reference>
                  <button type="button" class="more-btn" @click.stop>
                    {{ t('recordings.actions.more') }}
                    <el-icon :size="11"><ArrowDown /></el-icon>
                  </button>
                </template>
                <div class="more-list" @click.stop>
                  <button v-if="canClaimRow(row)" class="more-item" @click.stop="claim(row)">
                    {{ t('recordings.actions.claim') }}
                  </button>
                  <button
                    v-if="isAdmin && row.status === 'NEEDS_REVIEW'"
                    class="more-item"
                    @click.stop="openAssign(row)"
                  >
                    {{ t('recordings.actions.assign') }}
                  </button>
                  <el-popconfirm
                    v-if="isAdmin && isFinalReviewStatus(row.status)"
                    :title="t('recordings.reopen.confirm')"
                    width="260"
                    @confirm="reopen(row)"
                  >
                    <template #reference>
                      <button type="button" class="more-item warn" @click.stop>
                        {{ t('recordings.actions.reopen') }}
                      </button>
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
                      >
                        {{ reprocessInfo(row).label }}
                      </button>
                    </template>
                  </el-popconfirm>
                  <el-popconfirm
                    v-if="canWrite"
                    :title="t('recordings.delete.confirm')"
                    @confirm="remove(row)"
                  >
                    <template #reference>
                      <button type="button" class="more-item danger" @click.stop>
                        {{ t('recordings.actions.delete') }}
                      </button>
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
            <div class="empty-title">{{ t('recordings.empty.title') }}</div>
            <div class="tiny dim">{{ t('recordings.empty.hint') }}</div>
          </div>
        </template>
      </el-table>

      <div class="pager">
        <el-pagination
          layout="total, prev, pager, next"
          :total="total"
          :page-size="pageSize"
          :current-page="page + 1"
          @current-change="
            (p) => {
              page = p - 1
              load()
            }
          "
        />
      </div>
    </section>

    <!-- 详情抽屉 -->
    <el-drawer
      v-model="detailVisible"
      :title="t('nav.recordings')"
      size="720px"
      :with-header="false"
      append-to-body
      @closed="workspaceRef?.pause()"
    >
      <div v-loading="detailLoading" class="detail">
        <template v-if="detail">
          <!-- 抽屉头 -->
          <div class="detail-head">
            <div class="detail-title">
              <div class="file-icon lg">
                <el-icon :size="18"><Microphone /></el-icon>
              </div>
              <div class="min-w-0">
                <div class="detail-name ellipsis">{{ detail.fileName }}</div>
                <div class="detail-chips">
                  <StatusTag :status="detail.status" />
                  <span class="chip">#{{ detail.id }}</span>
                  <span class="chip">{{ formatDuration(detail.durationSeconds) }}</span>
                  <span class="chip">{{ formatSize(detail.fileSize) }}</span>
                  <span v-if="detail.language" class="chip">{{ detail.language }}</span>
                  <span
                    v-if="detailChannelKind === 'stereo'"
                    class="chip brand"
                    :title="t('recordings.detail.stereoTip')"
                    >{{ t('recordings.detail.stereo') }}</span
                  >
                  <span
                    v-else-if="detailChannelKind === 'mono'"
                    class="chip"
                    :title="t('recordings.detail.monoTip')"
                    >{{ t('recordings.detail.mono') }}</span
                  >
                  <span
                    v-if="isSampled(detail)"
                    class="chip warn"
                    :title="t('recordings.sampled.tooltip')"
                    >{{ t('recordings.sampled.label') }}</span
                  >
                  <PriorityTag :priority="detail.priority" />
                  <span class="chip">
                    {{
                      detail.assignedTo
                        ? t('recordings.detail.assignedTo', { name: detail.assignedTo })
                        : t('recordings.table.unassigned')
                    }}
                  </span>
                  <span v-if="detail.agentName" class="chip">{{
                    t('recordings.detail.agent', { name: detail.agentName })
                  }}</span>
                  <span v-if="detail.teamName" class="chip">{{
                    t('recordings.detail.team', { name: detail.teamName })
                  }}</span>
                  <span v-if="detail.businessLine" class="chip">{{
                    t('recordings.detail.businessLine', { name: detail.businessLine })
                  }}</span>
                  <span
                    v-if="detail.direction && DIRECTION_META[detail.direction]"
                    class="chip"
                    :class="directionClass(detail.direction)"
                    >{{ directionLabel(detail.direction) }}</span
                  >
                  <span v-if="detail.callTime" class="chip num">{{
                    t('recordings.detail.callTime', { time: formatTime(detail.callTime) })
                  }}</span>
                  <span v-if="detailProgress.required > 1" class="chip info">
                    {{ t('recordings.detail.reviewProgress', { progress: detailProgress.text }) }}
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
                >{{ t('recordings.actions.goManualReview') }}</el-button
              >
              <el-button
                v-if="canClaimRow(detail)"
                type="primary"
                size="small"
                @click="claim(detail)"
                >{{ t('recordings.actions.claim') }}</el-button
              >
              <el-button
                v-if="isAdmin && detail.status === 'NEEDS_REVIEW'"
                size="small"
                @click="openAssign(detail)"
                >{{ t('recordings.actions.assignShort') }}</el-button
              >
              <el-popconfirm
                v-if="isAdmin && isFinalReviewStatus(detail.status)"
                :title="t('recordings.reopen.confirm')"
                width="260"
                @confirm="reopen(detail)"
              >
                <template #reference>
                  <el-button type="warning" size="small">{{
                    t('recordings.actions.reopenShort')
                  }}</el-button>
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
              <el-button
                :icon="Close"
                circle
                size="small"
                :aria-label="t('common.close')"
                :title="t('common.close')"
                @click="detailVisible = false"
              />
            </div>
          </div>

          <!-- 详情工作台（播放 / 声道 / 转写 / AI 结论 / AI 留痕）：与复核页共用组件 -->
          <RecordingWorkspace
            ref="workspaceRef"
            :record="detail"
            :ai-invocations="aiInvocations"
            :admin="isAdmin"
            :labels="workspaceLabels"
          >
            <!-- 人工复检 -->
            <section v-if="detail.reviewResult" class="block">
              <div class="block-title">{{ t('recordings.detail.manualReview') }}</div>
              <div class="review-grid">
                <div class="review-item">
                  <span class="review-label">{{ t('recordings.review.result') }}</span>
                  <span
                    class="chip"
                    :class="detail.reviewResult === 'CONFIRMED_VIOLATION' ? 'danger' : 'ok'"
                  >
                    {{
                      detail.reviewResult === 'CONFIRMED_VIOLATION'
                        ? STATUS_META.VIOLATION_CONFIRMED.label
                        : STATUS_META.FALSE_POSITIVE.label
                    }}
                  </span>
                </div>
                <div class="review-item">
                  <span class="review-label">{{ t('common.type') }}</span>
                  <span>{{ detail.violationTypeLabel || '—' }}</span>
                </div>
                <div class="review-item">
                  <span class="review-label">{{ t('recordings.review.reviewer') }}</span>
                  <span>{{ detail.reviewer || '—' }}</span>
                </div>
                <div class="review-item">
                  <span class="review-label">{{ t('recordings.review.time') }}</span>
                  <span class="num">{{ formatTime(detail.reviewTime) }}</span>
                </div>
                <div class="review-item span-2">
                  <span class="review-label">{{ t('common.remark') }}</span>
                  <span>{{ detail.reviewComment || '—' }}</span>
                </div>
              </div>
            </section>

            <!-- 处理轨迹 -->
            <section class="block">
              <div class="block-title">{{ t('recordings.detail.timeline') }}</div>
              <el-timeline v-if="logs.length" class="timeline">
                <el-timeline-item
                  v-for="(l, i) in logs"
                  :key="i"
                  :type="
                    l.level === 'ERROR' ? 'danger' : l.level === 'WARN' ? 'warning' : 'primary'
                  "
                  :timestamp="formatTime(l.createdAt)"
                  size="normal"
                >
                  <span class="log-stage">{{ stageName(l.stage) }}</span>
                  <span class="log-msg">{{ l.message }}</span>
                </el-timeline-item>
              </el-timeline>
              <div v-else class="dim tiny">{{ t('recordings.detail.noLogs') }}</div>
            </section>
          </RecordingWorkspace>
        </template>
      </div>
    </el-drawer>

    <!-- 上传录音：文件 + 元数据（坐席 / 团队 / 通话时间 / 方向 / 业务线） -->
    <el-dialog
      v-model="uploadVisible"
      :title="t('recordings.upload.title')"
      width="540px"
      :close-on-click-modal="!uploading"
      :close-on-press-escape="!uploading"
      :show-close="!uploading"
      @closed="resetUploadForm"
    >
      <el-form :model="uploadForm" label-width="76px" class="upload-form">
        <el-form-item :label="t('recordings.upload.audioFiles')" required>
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
            <div class="el-upload__text">
              {{ t('recordings.upload.dropPrefix')
              }}<em>{{ t('recordings.upload.dropAccent') }}</em>
            </div>
            <template #tip>
              <div class="el-upload__tip">
                {{ t('recordings.upload.tip', { maxMb: MAX_UPLOAD_MB }) }}
              </div>
            </template>
          </el-upload>
        </el-form-item>
        <el-form-item :label="t('recordings.field.agent')">
          <el-input
            v-model="uploadForm.agentName"
            :placeholder="t('recordings.upload.agentPlaceholder')"
            clearable
            maxlength="64"
          />
        </el-form-item>
        <el-form-item :label="t('recordings.field.team')">
          <el-input
            v-model="uploadForm.teamName"
            :placeholder="t('recordings.upload.teamPlaceholder')"
            clearable
            maxlength="128"
          />
        </el-form-item>
        <el-form-item :label="t('recordings.field.callTime')">
          <el-date-picker
            v-model="uploadForm.callTime"
            type="datetime"
            :placeholder="t('recordings.upload.callTimePlaceholder')"
            format="YYYY-MM-DD HH:mm:ss"
            value-format="YYYY-MM-DDTHH:mm:ss"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item :label="t('recordings.field.direction')">
          <el-select
            v-model="uploadForm.direction"
            :placeholder="t('recordings.upload.directionPlaceholder')"
            clearable
            style="width: 100%"
          >
            <el-option
              v-for="opt in DIRECTION_OPTIONS"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('recordings.field.businessLine')">
          <el-input
            v-model="uploadForm.businessLine"
            :placeholder="t('recordings.upload.businessLinePlaceholder')"
            clearable
            maxlength="64"
          />
        </el-form-item>
      </el-form>

      <!-- 批量上传队列：逐文件进度 / 状态 / 汇总 -->
      <div v-if="uploadQueue.length" class="upload-queue">
        <div class="uq-head">
          <span class="tiny dim">
            {{
              countLabel('recordings.upload.queueSummary', uploadQueue.length, {
                done: uploadDoneCount
              })
            }}
          </span>
          <span v-if="uploading" class="tiny dim">{{
            t('recordings.upload.overallProgress', { percent: uploadOverallPercent })
          }}</span>
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
        {{
          t('recordings.upload.summary', {
            success: uploadSummary.success,
            error: uploadSummary.error,
            canceled: uploadSummary.canceled
          })
        }}
      </div>

      <div class="upload-tip tiny dim">
        {{ t('recordings.upload.metadataTip') }}
      </div>
      <template #footer>
        <el-button :disabled="uploading" @click="uploadVisible = false">{{
          t('common.cancel')
        }}</el-button>
        <el-button v-if="uploading" @click="cancelRemaining">{{
          t('recordings.upload.cancelRemaining')
        }}</el-button>
        <el-button type="primary" :loading="uploading" @click="submitUpload">
          {{
            uploading
              ? t('recordings.upload.uploadingProgress', {
                  done: uploadDoneCount,
                  count: uploadQueue.length
                })
              : t('recordings.upload.start')
          }}
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
import overviewState from '../state/overview'
import AssignDialog from '../components/AssignDialog.vue'
import RecordingWorkspace from '../components/RecordingWorkspace.vue'
import PriorityTag from '../components/PriorityTag.vue'
import StatusTag from '../components/StatusTag.vue'
import SegmentedControl from '../components/SegmentedControl.vue'
import { useI18n } from '../i18n'
import { dateShortcuts as buildDateShortcuts, toDateParam } from '../utils/dateRange'
import { channelKind, resolveChannelKind } from '../utils/segments.js'
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
  PROCESSING_STATUSES,
  STATUS_META
} from '../constants'
import { ArrowDown, Microphone, WarningFilled } from '../components/icons.js'

const { t } = useI18n()

/** 带数量的文案：英文按单复数取词典里的 `.one` / `.other` */
function countLabel(key, count, params = {}) {
  return t(`${key}.${count === 1 ? 'one' : 'other'}`, { ...params, count })
}

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
/** 概览统计与 App 全局轮询 / 其它页面共用一份（state/overview.js 单例，含 15s 缓存与请求去重） */
const overview = computed(() => overviewState.data)

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
// 上传大小上限：默认 200MB（与后端 APP_MAX_SIZE_BYTES=200MB / nginx 220m 对齐）；
// 可用 VITE_UPLOAD_MAX_MB 覆盖（如使用有单文件上限的外部 ASR 时调小）
const uploadMaxMb = Number(import.meta.env.VITE_UPLOAD_MAX_MB)
const MAX_UPLOAD_BYTES =
  (Number.isFinite(uploadMaxMb) && uploadMaxMb > 0 ? uploadMaxMb : 200) * 1024 * 1024
const MAX_UPLOAD_MB = Math.round(MAX_UPLOAD_BYTES / 1024 / 1024)
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
/** 详情声道分类：优先用持久化的 channelCount，历史数据用分段 L/R 标记兜底 */
const detailChannelKind = computed(() =>
  resolveChannelKind(detail.value, parseSegments(detail.value?.segmentsJson))
)
function parseSegments(raw) {
  try {
    const parsed = raw ? JSON.parse(raw) : []
    return Array.isArray(parsed) ? parsed : []
  } catch {
    return []
  }
}
const assignVisible = ref(false)
const assignTarget = ref(null)

/** 未认领的待复检录音可被 ADMIN / REVIEWER 认领 */
function canClaimRow(row) {
  return canWrite.value && row?.status === 'NEEDS_REVIEW' && !row.assignedTo
}

/* 筛选项文案：getter 保证语言切换后 `filters` 计算属性重新求值；
 * 状态类标签复用 constants 的响应式 getter，分组 / 简称（处理中、待复检）单独取词。 */
const FILTERS = [
  {
    key: '',
    countKey: 'ALL',
    get label() {
      return t('common.all')
    }
  },
  {
    key: 'PROCESSING',
    countKey: 'PROCESSING',
    get label() {
      return t('recordings.filters.processing')
    }
  },
  {
    key: 'NEEDS_REVIEW',
    countKey: 'NEEDS_REVIEW',
    get label() {
      return t('recordings.filters.needsReview')
    }
  },
  {
    key: 'COMPLIANT',
    countKey: 'COMPLIANT',
    get label() {
      return STATUS_META.COMPLIANT.label
    }
  },
  {
    key: 'VIOLATION_CONFIRMED',
    countKey: 'VIOLATION_CONFIRMED',
    get label() {
      return STATUS_META.VIOLATION_CONFIRMED.label
    }
  },
  {
    key: 'FALSE_POSITIVE',
    countKey: 'FALSE_POSITIVE',
    get label() {
      return STATUS_META.FALSE_POSITIVE.label
    }
  },
  {
    key: 'FAILED',
    countKey: 'FAILED',
    get label() {
      return STATUS_META.FAILED.label
    }
  }
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

/* 枚举型过滤统一用分段控件（外观与人工复检页一致）：
   状态带计数、待复检 >0 时角标标红；指派 / 优先级 / 方向提供「全部」项。 */
const statusOptions = computed(() =>
  filters.value.map((f) => ({
    value: f.key,
    label: f.label,
    count: f.count,
    hot: f.countKey === 'NEEDS_REVIEW' && (f.count ?? 0) > 0
  }))
)

const assignedOptions = computed(() => [
  { value: '', label: t('common.all') },
  { value: 'mine', label: t('recordings.assigned.mine') }
])

const priorityOptions = computed(() => [
  { value: '', label: t('common.all') },
  ...PRIORITY_OPTIONS.map((opt) => ({ value: opt.value, label: opt.label }))
])

const directionOptions = computed(() => [
  { value: '', label: t('common.all') },
  ...DIRECTION_OPTIONS.map((opt) => ({ value: opt.value, label: opt.label }))
])

/* ---------- 元数据筛选 ---------- */

/* 快捷日期：文案放在 computed 里，语言切换时重新求值（日期计算复用 utils/dateRange） */
const dateShortcuts = computed(() =>
  buildDateShortcuts({
    last7Days: t('recordings.meta.last7Days'),
    last30Days: t('recordings.meta.last30Days'),
    last90Days: t('recordings.meta.last90Days')
  })
)

const metaParams = computed(() => ({
  agentName: agentFilter.value.trim() || undefined,
  teamName: teamFilter.value.trim() || undefined,
  direction: directionFilter.value || undefined,
  // 空区间保持「不传」语义：toDateParam 空值返回 ''，这里转 undefined 交给 api 层剔除
  from: toDateParam(callDateRange.value?.[0]) || undefined,
  to: toDateParam(callDateRange.value?.[1]) || undefined
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

/* 处理轨迹阶段：getter 形式，切换语言后重新求值 */
const STAGE_NAMES = {
  get PIPELINE() {
    return t('recordings.stage.pipeline')
  },
  get TRANSCRIBE() {
    return t('recordings.stage.transcribe')
  },
  get DFA() {
    return t('recordings.stage.dfa')
  },
  get AI() {
    return t('recordings.stage.ai')
  },
  get REVIEW() {
    return t('recordings.stage.review')
  },
  get MINING() {
    return t('recordings.stage.mining')
  },
  get RAG() {
    return t('recordings.stage.rag')
  }
}
const stageName = (s) => STAGE_NAMES[s] || s

/* ---------- AI 调用留痕 ---------- */
/** AI 调用阶段复用 meta 里的枚举标签（未知阶段按语义复筛展示） */
const AI_STAGE_KEYS = {
  SCREEN: 'meta.aiStage.SCREEN',
  MINING: 'meta.aiStage.MINING',
  EMBED_QUERY: 'meta.aiStage.EMBED_QUERY',
  EMBED_INDEX: 'meta.aiStage.EMBED_INDEX'
}
const stageLabel = (s) => t(AI_STAGE_KEYS[s] || 'meta.aiStage.SCREEN')

/* 详情工作台文案：按 recordings.* 域名组装，传给共享的 RecordingWorkspace */
const workspaceLabels = computed(() => ({
  channelMix: t('recordings.channel.mix'),
  channelLeft: t('recordings.channel.left'),
  channelRight: t('recordings.channel.right'),
  noAudio: t('recordings.detail.noAudio'),
  noAudioSeek: (clock) => t('recordings.messages.noAudioSeek', { time: clock }),
  dialogue: t('recordings.transcript.dialogue'),
  segment: t('recordings.transcript.sentence'),
  full: t('recordings.transcript.full'),
  follow: t('recordings.transcript.follow'),
  emptyTranscript: t('recordings.transcript.empty'),
  verdictTitle: t('recordings.detail.aiScreening'),
  verdictViolation: t('recordings.detail.verdictViolation'),
  verdictCompliant: t('recordings.detail.verdictCompliant'),
  confidence: t('recordings.detail.confidence'),
  modelLabel: t('recordings.detail.model'),
  degraded: t('recordings.detail.degraded'),
  reasonFallback: '—',
  tracesTitle: t('recordings.detail.aiInvocations'),
  tracesCount: (count) => countLabel('recordings.detail.aiInvocationsHint', count),
  stageLabel,
  endpoint: t('recordings.detail.endpoint'),
  requestChars: (count) => t('recordings.detail.requestChars', { count }),
  responseChars: (count) => t('recordings.detail.responseChars', { count }),
  charCount: (count) => t('recordings.detail.charCount', { count }),
  noRequestBody: t('recordings.detail.noRequestBody'),
  noReply: t('recordings.detail.noReply')
}))

/** 抽屉关闭 / 卸载时暂停试听（详情工作台内部播放器由子组件暴露 pause） */
const workspaceRef = ref(null)

/**
 * 列表请求序号：筛选 / 翻页 / 3s 轮询可能并发，只允许最新一次请求写回 rows / total。
 * 与 KnowledgeIndex.vue 的 chunksToken 同一「最新请求胜出」范式。
 */
let loadSeq = 0

async function load() {
  const seq = ++loadSeq
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
    if (seq !== loadSeq) return
    rows.value = data.content || []
    total.value = data.totalElements || 0
  } finally {
    if (seq === loadSeq) loading.value = false
  }
}

/**
 * 概览统计：默认复用全局单例缓存（与 App 15s 轮询去重）；
 * 上传 / 删除 / 重新处理等写操作后传 force:true 立即刷新筛选计数。
 */
async function loadOverview({ force = false } = {}) {
  try {
    await overviewState.load({ force })
  } catch {
    // 忽略：失败时单例保留旧数据，计数缺失不阻塞列表
  }
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
 * 保留已有文件的上传状态与进度；超过上传上限（默认 200MB）的文件直接标记失败并跳过上传。
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
        return { ...prev, status: UPLOAD_STATUS.ERROR, error: t('recordings.upload.tooLarge') }
      }
      // 移除后重新选择同一文件：保留新 raw 引用
      return { ...prev, file: raw }
    }
    return {
      uid,
      file: raw,
      name: raw?.name || t('recordings.upload.unknownFile'),
      size: Number(raw?.size) || 0,
      progress: 0,
      status: tooLarge ? UPLOAD_STATUS.ERROR : UPLOAD_STATUS.PENDING,
      error: tooLarge ? t('recordings.upload.tooLarge', { maxMb: MAX_UPLOAD_MB }) : ''
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
  ElMessage.warning(t('recordings.upload.tooMany'))
}

/**
 * 单文件上传：网络层失败（请求未到达服务器，如网络抖动 / 代理中断）自动重试一次；
 * 有 HTTP 响应的错误（4xx/5xx）不重试，直接展示原因，避免重复提交。
 */
async function uploadOnceWithRetry(item) {
  for (let attempt = 0; ; attempt += 1) {
    try {
      // silent：失败统一由页面汇总提示（api.upload 支持 silent 后不再逐文件弹全局错误）
      return await api.upload(item.file, uploadForm.value, {
        silent: true,
        onUploadProgress: (event) => {
          uploadQueue.value = updateQueueItem(uploadQueue.value, item.uid, {
            progress: progressFromEvent(event)
          })
        }
      })
    } catch (error) {
      if (attempt === 0 && !error?.response) {
        await new Promise((resolve) => setTimeout(resolve, 800))
        continue
      }
      throw error
    }
  }
}

/** 上传失败原因：优先用后端返回的 message；网络层失败（无响应）给出可操作提示 */
function describeUploadError(error) {
  const status = error?.response?.status
  if (status) {
    const message = error?.response?.data?.message
    return message
      ? t('recordings.upload.httpError', { status }) + '：' + message
      : t('recordings.upload.httpError', { status })
  }
  if (error?.code === 'ECONNABORTED' || error?.code === 'ETIMEDOUT') {
    return t('recordings.upload.timeoutError')
  }
  return t('recordings.upload.networkError')
}

/** 取消剩余：正在上传的当前文件会继续完成，未开始的标记为已取消 */
function cancelRemaining() {
  if (!uploading.value) return
  cancelRequested = true
  uploadQueue.value = markRemainingCanceled(uploadQueue.value)
  ElMessage.info(t('recordings.upload.canceledRemaining'))
}

async function submitUpload() {
  if (!uploadQueue.value.length) {
    ElMessage.warning(t('recordings.upload.noFilesSelected'))
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
        ? t('recordings.upload.noUploadable', { maxMb: MAX_UPLOAD_MB })
        : t('recordings.upload.batchDone')
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
        const result = await uploadOnceWithRetry(current)
        uploadQueue.value = updateQueueItem(uploadQueue.value, queued.uid, {
          status: UPLOAD_STATUS.SUCCESS,
          progress: 100,
          resultId: result?.id
        })
        success += 1
      } catch (e) {
        uploadQueue.value = updateQueueItem(uploadQueue.value, queued.uid, {
          status: UPLOAD_STATUS.ERROR,
          error: describeUploadError(e)
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
    ElMessage.success(countLabel('recordings.upload.successMessage', success))
    reload()
    loadOverview({ force: true })
  }
  if (failed > 0) ElMessage.error(countLabel('recordings.upload.failureMessage', failed))

  // 保持单文件旧体验：单个文件全部成功时自动关闭并清空
  if (totalFiles === 1 && summary.success === 1) {
    uploadVisible.value = false
    resetUploadForm()
  }
}

/**
 * 详情请求序号：连续点开不同录音（或轮询刷新）时只保留最后一次响应。
 * 同时这里对单条请求分别兜底：AI 留痕接口已收紧为 ADMIN，
 * 非 ADMIN 不发起请求（避免 403 噪音），任一子请求失败也不影响详情主体。
 */
let detailSeq = 0

async function openDetail(row) {
  if (!row?.id) return
  const seq = ++detailSeq
  detailVisible.value = true
  detailLoading.value = true
  detail.value = null
  logs.value = []
  aiInvocations.value = []
  try {
    const [d, l, inv] = await Promise.all([
      api.recordingDetail(row.id).catch(() => null),
      api.recordingLogs(row.id).catch(() => []),
      isAdmin.value ? api.recordingAiInvocations(row.id).catch(() => []) : Promise.resolve([])
    ])
    if (seq !== detailSeq) return
    if (!d) return
    detail.value = d
    logs.value = l || []
    aiInvocations.value = inv || []
  } finally {
    if (seq === detailSeq) detailLoading.value = false
  }
}

/** 手动推进/重新处理：清空上一轮产物后重新进入流水线 */
async function reprocess(row) {
  const info = reprocessInfo(row)
  await api.reprocessRecording(row.id)
  ElMessage.success(
    t('recordings.reprocess.triggered', {
      action: info?.label || t('recordings.reprocess.fallbackAction')
    })
  )
  load()
  loadOverview({ force: true })
  if (detail.value?.id === row.id) openDetail(row)
}

async function remove(row) {
  await api.deleteRecording(row.id)
  ElMessage.success(t('recordings.messages.deleted'))
  if (detail.value?.id === row.id) detailVisible.value = false
  load()
  loadOverview({ force: true })
}

/* ---------- 复核工作流：认领 / 指派 / 重开 ---------- */

/** 认领：仅未指派的待复检录音；已被他人认领时后端返回 409 */
async function claim(row) {
  if (!row?.id) return
  try {
    const saved = await api.claimRecording(row.id)
    const assignedTo = saved?.assignedTo || auth.username
    const patch = saved && typeof saved === 'object' ? saved : {}
    rows.value = rows.value.map((r) => (r.id === row.id ? { ...r, ...patch, assignedTo } : r))
    if (detail.value?.id === row.id) {
      detail.value = { ...detail.value, ...patch, assignedTo }
    }
    ElMessage.success(t('recordings.messages.claimed'))
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
    ElMessage.success(t('recordings.messages.reopened'))
    if (detail.value?.id === row.id) detailVisible.value = false
    await load()
    loadOverview({ force: true })
  } catch {
    // 请求层已统一提示
  }
}

let timer = null
const hasProcessing = () => rows.value.some((r) => PROCESSING_STATUSES.includes(r.status))

/** 3s 自动刷新：页面不可见 / 已有列表请求在途时跳过，避免无效请求与响应叠加 */
function tick() {
  if (document.hidden || loading.value || !hasProcessing()) return
  load()
  loadOverview()
}

function onVisibilityChange() {
  if (document.hidden || loading.value || !hasProcessing()) return
  load()
  loadOverview()
}

onMounted(async () => {
  observeTableWidth()
  // 支持从工作台/其他页面带条件跳转
  const qs = String(route.query.statuses || '')
  if (qs) statusFilter.value = qs.split(',')[0] === 'FAILED' ? 'FAILED' : qs.split(',')[0]
  loadOverview()
  // 先启动轮询再拉首屏：首屏请求失败（后端未就绪等）时仍能自动恢复，且不中断后续逻辑
  timer = setInterval(tick, 3000)
  document.addEventListener('visibilitychange', onVisibilityChange)
  try {
    await load()
  } catch {
    // 请求层已统一提示：保留空列表，定时器会在后端恢复后自动刷新
  }
  if (route.query.id) {
    openDetail({ id: Number(route.query.id) })
  }
})
onUnmounted(() => {
  if (timer) clearInterval(timer)
  timer = null
  document.removeEventListener('visibilitychange', onVisibilityChange)
  tableObserver?.disconnect()
  tableObserver = null
})
</script>

<style scoped>
/* 工具栏 */
/* 状态 chips 与右侧筛选/上传操作同排：宽屏一行放得下时左右对齐，
   放不下时自动换行并保持操作区右对齐（窄屏与原来两行观感一致） */
.toolbar {
  display: flex;
  flex-direction: row;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  flex-wrap: wrap;
  padding: 16px 18px 12px;
}
.filters {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  min-width: 0;
}
.actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 7px;
  flex-wrap: wrap;
  margin-left: auto;
}
.actions :deep(.el-input__wrapper),
.actions :deep(.el-select__wrapper) {
  min-height: 32px;
}
.search {
  width: 224px;
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
.call-time {
  color: var(--ink-700);
  white-space: nowrap;
}
.call-duration {
  color: var(--ink-600);
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
  transition:
    background 0.15s ease,
    color 0.15s ease;
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
/* AI 结论 */
/* 判定模型标注 */
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
