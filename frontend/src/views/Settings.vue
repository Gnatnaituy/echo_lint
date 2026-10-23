<template>
  <div class="page stack">
    <!-- ① 当前生效 -->
    <section class="panel">
      <div class="panel-head">
        <div class="section-title">{{ t('settings.active.title') }}</div>
        <span class="tiny dim">{{ t('settings.active.subtitle') }}</span>
      </div>
      <div class="panel-body">
        <div v-if="view" class="active-card">
          <div class="active-left">
            <div class="active-mark" :class="view.apiKeyConfigured ? 'ok' : 'warn'">
              <el-icon :size="19"><Cpu /></el-icon>
            </div>
            <div class="active-info">
              <div class="active-line">
                <span class="active-provider">{{ view.providerLabel }}</span>
                <span class="active-sep">·</span>
                <span class="active-model mono">{{ view.model }}</span>
                <span class="chip" :class="view.source === 'DATABASE' ? 'brand' : 'info'">
                  {{ view.source === 'DATABASE' ? t('settings.source.uiSwitch') : t('settings.source.configDefault') }}
                </span>
              </div>
              <div class="active-status">
                <span class="dot" :style="{ background: view.apiKeyConfigured ? 'var(--ok)' : 'var(--warn)' }"></span>
                <span :class="view.apiKeyConfigured ? 'dim' : 'warn-text'">
                  {{ view.apiKeyConfigured
                    ? t('settings.active.keyConfigured')
                    : t('settings.active.keyMissing') }}
                </span>
              </div>
            </div>
          </div>
          <el-button
            v-if="canWrite"
            :loading="testing === 'active'"
            :disabled="testing === 'draft'"
            @click="testActive"
          >
            <el-icon v-if="testing !== 'active'" class="el-icon--left"><Connection /></el-icon>
            {{ t('settings.active.testConnection') }}
          </el-button>
        </div>
        <div v-else class="empty-block">{{ t('common.loading') }}</div>

        <div v-if="testResult" class="test-result" :class="testResult.ok ? 'ok' : 'bad'">
          <el-icon :size="16">
            <component :is="testResult.ok ? 'CircleCheckFilled' : 'CircleCloseFilled'" />
          </el-icon>
          <div class="test-text">
            <div class="test-head">
              <b>{{ testResult.ok ? t('settings.test.ok') : t('settings.test.fail') }}</b>
              <span class="mono dim">{{ testResult.providerLabel }} · {{ testResult.model }}</span>
              <span v-if="testResult.ok" class="num dim">{{ t('settings.test.latency', { ms: testResult.latencyMs }) }}</span>
            </div>
            <div class="test-msg">{{ testResult.ok ? (testResult.reply || t('settings.test.emptyReply')) : testResult.message }}</div>
          </div>
        </div>
      </div>
    </section>

    <!-- ② 切换 -->
    <section class="panel">
      <div class="panel-head">
        <div class="section-title">{{ t('settings.model.title') }}</div>
        <span class="tiny dim">{{ t('settings.model.subtitle') }}</span>
      </div>
      <div class="panel-body">
        <template v-if="canWrite">
        <div class="provider-grid">
          <button
            v-for="p in view?.providers || []"
            :key="p.id"
            type="button"
            class="provider-card"
            :class="{ on: p.id === draft.providerId, off: isProviderDisabled(p.id) }"
            :disabled="isProviderDisabled(p.id)"
            :title="isProviderDisabled(p.id) ? t('settings.model.providerDisabled') : p.baseUrl"
            @click="pickProvider(p)"
          >
            <div class="pc-head">
              <span class="pc-name">{{ p.label }}</span>
              <span v-if="p.active" class="chip brand">{{ t('settings.provider.current') }}</span>
            </div>
            <div class="pc-url mono">{{ p.baseUrl }}</div>
            <div class="pc-foot">
              <span class="dot" :style="{ background: p.apiKeyConfigured ? 'var(--ok)' : 'var(--warn)' }"></span>
              <span class="tiny">
                {{ p.apiKeyConfigured ? t('settings.model.keyConfigured') : (p.apiKeyEnv ? t('settings.model.keyMissingEnv', { env: p.apiKeyEnv }) : t('settings.model.keyMissing')) }}
              </span>
            </div>
          </button>
        </div>

        <div class="switch-row">
          <div class="field">
            <label class="field-label">{{ t('settings.model.label') }}</label>
            <el-select
              v-model="draft.model"
              class="model-select"
              filterable
              allow-create
              default-first-option
              :placeholder="t('settings.model.placeholder')"
            >
              <el-option v-for="m in currentProvider?.models || []" :key="m" :label="m" :value="m" />
            </el-select>
          </div>
          <div class="switch-actions">
            <el-button :loading="testing === 'draft'" :disabled="!draft.providerId" @click="testDraft">
              {{ t('settings.model.test') }}
            </el-button>
            <el-button type="primary" :loading="saving" :disabled="!dirty" @click="save">
              {{ t('settings.model.save') }}
            </el-button>
          </div>
        </div>
        <div class="hint tiny dim">
          {{ t('settings.model.hintSelect') }}
          {{ t('settings.model.hintEffect') }}
        </div>
        </template>
        <el-alert
          v-else
          type="info"
          :closable="false"
          show-icon
          :title="t('settings.model.readonlyAlert')"
        />
      </div>
    </section>

    <!-- ③ AI 供应商（ADMIN）：注册表管理，仍可切换当前生效供应商 / 模型 -->
    <section v-if="canWrite" class="panel">
      <div class="panel-head">
        <div class="section-title">{{ t('settings.provider.title') }}</div>
        <div class="provider-head">
          <span class="tiny dim">{{ t('settings.provider.subtitle') }}</span>
          <el-button size="small" :loading="providersLoading" @click="loadProviders">{{ t('common.refresh') }}</el-button>
          <el-button size="small" type="primary" @click="openProviderCreate">
            <el-icon class="el-icon--left"><Plus /></el-icon>
            {{ t('settings.provider.create') }}
          </el-button>
        </div>
      </div>
      <div v-loading="providersLoading" class="panel-body">
        <!-- 接口未上线 / 无权限（403、404）时降级为可重试提示，不影响上方切换模型 -->
        <div v-if="providersError" class="provider-fallback">
          <el-icon :size="16"><WarningFilled /></el-icon>
          <div class="provider-fallback-text">
            <div class="strong">{{ t('settings.provider.unavailable') }}</div>
            <div class="tiny dim">{{ providersError }}</div>
          </div>
          <el-button size="small" @click="loadProviders">{{ t('common.retry') }}</el-button>
        </div>

        <template v-else>
          <!-- 表格贴齐卡片左右边缘：首列文字与 panel-head 标题在 18px 基线上对齐（避免 18+18 双重缩进） -->
          <div ref="providerTableWrap" class="table-bleed">
            <el-table :data="providers" size="small">
              <el-table-column :label="t('settings.provider.columnName')" min-width="180">
                <template #default="{ row }">
                  <div class="provider-cell">
                    <div class="provider-name">
                      <span class="strong">{{ row.label || row.key || t('common.untitled') }}</span>
                      <span v-if="providerIsActive(row)" class="chip brand">{{ t('settings.provider.current') }}</span>
                      <span v-if="row.builtIn" class="chip info">{{ t('settings.provider.builtIn') }}</span>
                    </div>
                    <div class="mono tiny dim">{{ row.key || '—' }}</div>
                  </div>
                </template>
              </el-table-column>

              <el-table-column :label="t('settings.provider.baseUrl')" min-width="190">
                <template #default="{ row }">
                  <div class="provider-cell">
                    <div class="mono tiny ellipsis" :title="row.baseUrl">{{ row.baseUrl || '—' }}</div>
                    <div class="mono tiny dim ellipsis" :title="row.chatPath">
                      {{ row.chatPath || t('settings.provider.defaultChatPath') }}
                    </div>
                  </div>
                </template>
              </el-table-column>

              <el-table-column :label="t('settings.provider.defaultModel')" min-width="142">
                <template #default="{ row }">
                  <div class="mono tiny ellipsis" :title="row.defaultModel">{{ row.defaultModel || '—' }}</div>
                  <div class="tiny dim">{{ t('settings.provider.modelCount', { count: row.models.length }) }}</div>
                </template>
              </el-table-column>

              <el-table-column v-if="!compactProviderTable" :label="t('settings.provider.pricePer1k')" width="126" align="right">
                <template #header>
                  <span :title="t('settings.provider.priceTooltip')">{{ t('settings.provider.pricePer1k') }}</span>
                </template>
                <template #default="{ row }">
                  <div class="price-cell">
                    <span class="price-row">
                      <span class="price-label">{{ t('settings.provider.priceIn') }}</span>
                      <span class="mono">{{ formatPricePer1k(row.inputPricePer1k) }}</span>
                    </span>
                    <span class="price-row">
                      <span class="price-label">{{ t('settings.provider.priceOut') }}</span>
                      <span class="mono">{{ formatPricePer1k(row.outputPricePer1k) }}</span>
                    </span>
                  </div>
                </template>
              </el-table-column>

              <el-table-column :label="t('settings.provider.keyColumn')" width="108">
                <template #default="{ row }">
                  <span class="chip mono key-chip" :class="providerKeyChip(row).type">
                    {{ providerKeyChip(row).label }}
                  </span>
                </template>
              </el-table-column>

              <el-table-column :label="t('common.status')" width="82" align="right">
                <template #default="{ row }">
                  <span class="chip" :class="row.enabled === false ? 'info' : row.enabled === true ? 'ok' : 'info'">
                    {{ row.enabled === false ? t('common.disabled') : row.enabled === true ? t('common.enabled') : t('common.unknown') }}
                  </span>
                </template>
              </el-table-column>

              <el-table-column :label="t('common.actions')" width="138" align="right">
                <template #default="{ row }">
                  <el-button size="small" link type="primary" @click="openProviderTest(row)">{{ t('settings.provider.test') }}</el-button>
                  <el-button size="small" link type="primary" @click="openProviderEdit(row)">{{ t('common.edit') }}</el-button>
                  <el-tooltip
                    v-if="!row.builtIn"
                    :disabled="!providerIsActive(row)"
                    :content="t('settings.provider.activeDeleteTooltip')"
                    placement="top"
                  >
                    <span class="delete-wrap">
                      <el-popconfirm
                        :title="t('settings.provider.deleteConfirm')"
                        width="270"
                        @confirm="removeProvider(row)"
                      >
                        <template #reference>
                          <el-button size="small" link type="danger" :disabled="providerIsActive(row)">
                            {{ t('common.delete') }}
                          </el-button>
                        </template>
                      </el-popconfirm>
                    </span>
                  </el-tooltip>
                </template>
              </el-table-column>

              <template #empty>
                <div class="empty-block">
                  <el-icon :size="34" class="empty-icon"><Cpu /></el-icon>
                  <div class="empty-title">{{ t('settings.provider.empty') }}</div>
                  <div class="tiny dim">
                    {{ t('settings.provider.emptyHint') }}
                  </div>
                </div>
              </template>
            </el-table>
          </div>

          <div class="hint tiny dim">
            {{ t('settings.provider.hintA') }}
            {{ t('settings.provider.hintB') }}
          </div>
        </template>
      </div>
    </section>

    <!-- ④ A/B 与模型评测（ADMIN）：A/B 配置只读展示 + 手动评测 -->
    <section v-if="canWrite" class="panel">
      <div class="panel-head">
        <div class="section-title">{{ t('settings.ab.title') }}</div>
        <span class="tiny dim">{{ t('settings.ab.subtitle') }}</span>
      </div>
      <div class="panel-body">
        <!-- A/B 配置 -->
        <div v-if="abConfig" class="ab-grid">
          <div class="ab-item">
            <div class="ab-label">{{ t('settings.ab.split') }}</div>
            <div class="ab-value">
              <span class="chip" :class="abState.type">{{ abState.label }}</span>
            </div>
            <div class="ab-sub tiny dim">{{ abState.note }}</div>
          </div>
          <div class="ab-item">
            <div class="ab-label">{{ t('settings.ab.percent') }}</div>
            <div class="ab-value num">{{ formatAbPercent(abConfig.percent) }}</div>
            <div class="ab-sub tiny dim">
              {{ t('settings.ab.percentNote') }}
            </div>
          </div>
          <div class="ab-item">
            <div class="ab-label">{{ t('settings.ab.group') }}</div>
            <div class="ab-value ab-b">
              <span class="strong ellipsis">{{ abProviderLabel }}</span>
              <span class="chip info mono">{{ abConfig.model || t('settings.ab.providerDefault') }}</span>
            </div>
            <div class="ab-sub tiny dim">{{ t('settings.ab.groupNote') }}</div>
          </div>
        </div>
        <div v-else class="eval-empty tiny dim">
          {{ view
            ? t('settings.ab.missing')
            : t('settings.ab.pending') }}
        </div>

        <!-- 评测表单 -->
        <div class="eval-form">
          <div class="eval-field">
            <label class="eval-label">{{ t('settings.eval.provider') }}</label>
            <el-select
              v-model="evalForm.providerId"
              :disabled="evaluating"
              :placeholder="t('settings.eval.providerPlaceholder')"
              @change="onEvalProviderChange"
            >
              <el-option
                v-for="opt in evalProviderOptions"
                :key="opt.value"
                :label="opt.label"
                :value="opt.value"
              />
            </el-select>
          </div>
          <div class="eval-field">
            <label class="eval-label">{{ t('settings.eval.modelLabel') }}</label>
            <el-select
              v-model="evalForm.model"
              :disabled="evaluating"
              filterable
              allow-create
              clearable
              default-first-option
              :placeholder="t('settings.eval.modelPlaceholder')"
            >
              <el-option v-for="m in evalModelOptions" :key="m" :label="m" :value="m" />
            </el-select>
          </div>
          <div class="eval-field eval-size">
            <label class="eval-label">{{ t('settings.eval.sampleSize') }}</label>
            <el-input-number
              v-model="evalForm.sampleSize"
              :min="1"
              :max="20"
              :precision="0"
              :disabled="evaluating"
              controls-position="right"
              style="width: 100%"
            />
          </div>
          <div class="eval-actions">
            <el-button
              type="primary"
              :loading="evaluating"
              :disabled="!evalForm.providerId || evaluating"
              @click="runEvaluation"
            >
              <el-icon v-if="!evaluating" class="el-icon--left"><DataAnalysis /></el-icon>
              {{ t('settings.eval.run') }}
            </el-button>
            <span v-if="!evalProviderOptions.length" class="tiny dim">
              {{ t('settings.eval.providerUnavailable') }}
            </span>
          </div>
        </div>

        <div v-if="evaluating" class="eval-running tiny dim">
          <el-icon class="is-loading"><Loading /></el-icon>
          {{ t('settings.eval.running') }}
        </div>

        <!-- 卡内错误 / 403/404 降级：已有结果时保留旧结果并提示 -->
        <div v-if="evaluationError" class="eval-error">
          <el-icon :size="16"><WarningFilled /></el-icon>
          <div class="eval-error-text">
            <div class="strong">{{ t('settings.eval.incomplete') }}</div>
            <div class="tiny dim">{{ evaluationError }}</div>
          </div>
          <el-button size="small" :disabled="evaluating || !evalForm.providerId" @click="runEvaluation">
            {{ t('common.retry') }}
          </el-button>
        </div>

        <!-- 评测结果 -->
        <template v-if="evaluation">
          <div class="eval-kpis">
            <div class="eval-kpi">
              <div class="ab-label">Accuracy</div>
              <div class="ab-value num">{{ formatRatioPercent(evaluation.accuracy) }}</div>
            </div>
            <div class="eval-kpi">
              <div class="ab-label">Precision</div>
              <div class="ab-value num">{{ formatRatioPercent(evaluation.precision) }}</div>
            </div>
            <div class="eval-kpi">
              <div class="ab-label">Recall</div>
              <div class="ab-value num">{{ formatRatioPercent(evaluation.recall) }}</div>
            </div>
            <div class="eval-kpi">
              <div class="ab-label">{{ t('settings.eval.evaluatedRatio') }}</div>
              <div class="ab-value num">{{ evaluatedText }}</div>
              <div class="ab-sub tiny dim">
                {{ t('settings.eval.correctFailed', { correct: evaluation.correct ?? '—', failed: evaluation.failed ?? '—' }) }}
              </div>
            </div>
          </div>

          <div class="confusion-row">
            <div class="confusion-grid">
              <div class="conf-cell tp">
                <span class="conf-label">TP</span><span class="conf-value num">{{ evaluation.tp }}</span>
              </div>
              <div class="conf-cell fp">
                <span class="conf-label">FP</span><span class="conf-value num">{{ evaluation.fp }}</span>
              </div>
              <div class="conf-cell tn">
                <span class="conf-label">TN</span><span class="conf-value num">{{ evaluation.tn }}</span>
              </div>
              <div class="conf-cell fn">
                <span class="conf-label">FN</span><span class="conf-value num">{{ evaluation.fn }}</span>
              </div>
            </div>
            <div class="conf-legend tiny dim">
              <span>{{ t('settings.eval.total', { total: evaluation.total ?? '—' }) }}</span>
              <span class="sep">·</span>
              <span>{{ t('settings.eval.latency', { value: formatLatencyMs(evaluation.latencyMs) }) }}</span>
              <span class="sep">·</span>
              <span>{{ t('settings.eval.failedExcluded') }}</span>
            </div>
          </div>

          <div class="eval-section-head">
            <div class="block-title">{{ t('settings.eval.byLabelTitle') }}</div>
            <span class="tiny dim">{{ t('settings.eval.byLabelNote') }}</span>
          </div>
          <div class="table-bleed">
            <el-table :data="evaluation.byLabel" size="small">
              <el-table-column :label="t('settings.eval.labelColumn')" min-width="140">
                <template #default="{ row }">
                  <span class="chip" :class="evaluationLabelMeta(row.label).type">
                    {{ evaluationLabelMeta(row.label).label }}
                  </span>
                </template>
              </el-table-column>
              <el-table-column :label="t('settings.eval.sampleSize')" width="110" align="right">
                <template #default="{ row }">
                  <span class="num">{{ row.total }}</span>
                </template>
              </el-table-column>
              <el-table-column :label="t('settings.eval.correct')" width="110" align="right">
                <template #default="{ row }">
                  <span class="num">{{ row.correct }}</span>
                </template>
              </el-table-column>
              <el-table-column :label="t('settings.eval.accuracy')" width="130" align="right">
                <template #default="{ row }">
                  <span class="num">{{ formatRatioPercent(labelAccuracy(row)) }}</span>
                </template>
              </el-table-column>
              <template #empty>
                <div class="empty-block">
                  <div class="empty-title">{{ t('settings.eval.byLabelEmpty') }}</div>
                  <div class="tiny dim">{{ t('settings.eval.byLabelEmptyHint') }}</div>
                </div>
              </template>
          </el-table>
          </div>

          <div class="eval-section-head">
            <div class="block-title">{{ t('settings.eval.resultsTitle') }}</div>
            <span class="tiny dim">{{ t('settings.eval.resultsNote', { count: evaluation.results.length }) }}</span>
          </div>
          <div class="table-bleed">
            <el-table :data="evaluation.results" size="small" max-height="420">
              <el-table-column :label="t('settings.eval.corpusId')" width="110">
                <template #default="{ row }">
                  <span class="mono">{{ row.corpusId || '—' }}</span>
                </template>
              </el-table-column>
              <el-table-column :label="t('settings.eval.expected')" width="100" align="center">
                <template #default="{ row }">
                  <span class="chip" :class="evaluationLabelMeta(row.expected).type">
                    {{ evaluationLabelMeta(row.expected).label }}
                  </span>
                </template>
              </el-table-column>
              <el-table-column :label="t('settings.eval.predicted')" width="100" align="center">
                <template #default="{ row }">
                  <span v-if="row.error" class="chip danger">{{ t('common.failed') }}</span>
                  <span v-else class="chip" :class="evaluationLabelMeta(row.predicted).type">
                    {{ evaluationLabelMeta(row.predicted).label }}
                  </span>
                </template>
              </el-table-column>
              <el-table-column :label="t('settings.eval.confidence')" width="100" align="right">
                <template #default="{ row }">
                  <span class="num">{{ row.confidence == null ? '—' : formatRatioPercent(row.confidence) }}</span>
                </template>
              </el-table-column>
              <el-table-column :label="t('settings.eval.reason')" min-width="280">
                <template #default="{ row }">
                  <el-tooltip
                    v-if="row.error || row.reason"
                    :content="row.error || row.reason"
                    placement="top"
                    :show-after="300"
                    :popper-style="{ maxWidth: '520px', wordBreak: 'break-all' }"
                  >
                    <span class="eval-reason" :class="{ err: !!row.error }">
                      {{ row.error || row.reason }}
                    </span>
                  </el-tooltip>
                  <span v-else class="dim">—</span>
                </template>
              </el-table-column>
              <el-table-column :label="t('common.status')" width="88" align="center">
                <template #default="{ row }">
                  <span class="chip" :class="row.error ? 'danger' : 'ok'">
                    {{ row.error ? t('common.failed') : t('common.success') }}
                  </span>
                </template>
              </el-table-column>
              <template #empty>
                <div class="empty-block">
                  <div class="empty-title">{{ t('settings.eval.resultsEmpty') }}</div>
                  <div class="tiny dim">{{ t('settings.eval.resultsEmptyHint') }}</div>
                </div>
              </template>
          </el-table>
          </div>
        </template>

        <!-- 空态 -->
        <div v-else-if="!evaluating && !evaluationError" class="eval-empty tiny dim">
          {{ t('settings.eval.empty') }}
          {{ t('settings.eval.emptyHint') }}
        </div>

        <div class="hint tiny dim">
          {{ t('settings.eval.hintA') }}
          {{ t('settings.eval.hintB') }}
        </div>
      </div>
    </section>

    <!-- ⑤ RAG 检索增强（ADMIN）：embedding 复用 AI 供应商；检索参数、索引统计与效果评测 -->
    <section v-if="canWrite" class="panel">
      <div class="panel-head">
        <div class="section-title">{{ t('settings.rag.title') }}</div>
        <div class="provider-head">
          <span class="tiny dim">{{ t('settings.rag.subtitle') }}</span>
          <el-button size="small" :loading="ragLoading" @click="loadRag">{{ t('common.refresh') }}</el-button>
        </div>
      </div>
      <div v-loading="ragLoading" class="panel-body">
        <!-- 接口未上线 / 无权限（403、404）时降级为可重试提示，不影响其他设置 -->
        <div v-if="ragError" class="notify-fallback">
          <el-icon :size="16"><WarningFilled /></el-icon>
          <div class="notify-fallback-text">
            <div class="strong">{{ t('settings.rag.unavailable') }}</div>
            <div class="tiny dim">{{ ragError }}</div>
          </div>
          <el-button size="small" @click="loadRag">{{ t('common.retry') }}</el-button>
        </div>

        <template v-else-if="rag">
          <!-- 总开关 -->
          <div class="rag-switch-row">
            <div class="rag-switch-main">
              <div class="rag-switch-line">
                <span class="strong">{{ t('settings.rag.enableLabel') }}</span>
                <span class="chip" :class="ragForm.enabled ? 'ok' : 'info'">
                  {{ ragForm.enabled ? t('settings.state.enabled') : t('settings.state.disabled') }}
                </span>
                <span v-if="rag.source" class="chip info">{{ t('settings.rag.source', { source: ragSourceLabel(rag.source) }) }}</span>
              </div>
              <div class="tiny dim rag-note">
                {{ t('settings.rag.enableNoteA') }}
                {{ t('settings.rag.enableNoteB') }}
              </div>
            </div>
            <el-switch
              v-model="ragForm.enabled"
              :loading="ragEnabledSaving"
              :disabled="ragEnabledSaving"
              @change="saveRagEnabled"
            />
          </div>

          <!-- embedding 只读信息 -->
          <div class="rag-section-head">
            <div class="block-title">{{ t('settings.rag.embeddingTitle') }}</div>
            <el-button size="small" :loading="ragTesting" :disabled="ragTesting" @click="testRagEmbedding">
              <el-icon v-if="!ragTesting" class="el-icon--left"><Connection /></el-icon>
              {{ t('settings.rag.testConnection') }}
            </el-button>
          </div>
          <div class="rag-kv-grid">
            <div class="rag-kv">
              <div class="notify-label">{{ t('settings.rag.providerModel') }}</div>
              <div class="rag-kv-value strong">{{ rag.embedding.providerLabel || '—' }}</div>
              <div class="mono tiny dim ellipsis" :title="rag.embedding.model">{{ rag.embedding.model || '—' }}</div>
            </div>
            <div class="rag-kv">
              <div class="notify-label">{{ t('settings.rag.requestPath') }}</div>
              <div class="rag-kv-value mono">{{ rag.embedding.path || '—' }}</div>
              <div class="tiny dim">{{ t('settings.rag.reuseHint') }}</div>
            </div>
            <div class="rag-kv">
              <div class="notify-label">{{ t('settings.rag.dimensions') }}</div>
              <div class="rag-kv-value num">{{ rag.embedding.dimensions ?? '—' }}</div>
              <div class="tiny dim">{{ t('settings.rag.timeout', { seconds: rag.embedding.timeoutSeconds ?? '—' }) }}</div>
            </div>
            <div class="rag-kv">
              <div class="notify-label">API Key</div>
              <div class="rag-kv-value">
                <span class="chip" :class="embeddingKeyChip(rag.embedding.apiKeyConfigured).type">
                  {{ embeddingKeyChip(rag.embedding.apiKeyConfigured).label }}
                </span>
              </div>
              <div class="tiny dim">{{ t('settings.rag.keyNote') }}</div>
            </div>
          </div>

          <div v-if="ragTestResult" class="test-result" :class="ragTestResult.ok === true ? 'ok' : 'bad'">
            <el-icon :size="16">
              <component :is="ragTestResult.ok === true ? 'CircleCheckFilled' : 'CircleCloseFilled'" />
            </el-icon>
            <div class="test-text">
              <div class="test-head">
                <b>{{ ragTestResult.ok === true ? t('settings.rag.testOk') : t('settings.rag.testFail') }}</b>
                <span v-if="ragTestResult.providerLabel || ragTestResult.model" class="mono dim">
                  {{ ragTestResult.providerLabel || '—' }} · {{ ragTestResult.model || '—' }}
                </span>
                <span v-if="ragTestResult.dimensions != null" class="num dim">
                  {{ t('settings.rag.dimensionValue', { dimensions: ragTestResult.dimensions }) }}
                </span>
                <span v-if="ragTestResult.latencyMs != null" class="num dim">
                  {{ t('settings.test.latency', { ms: ragTestResult.latencyMs }) }}
                </span>
              </div>
              <div class="test-msg">
                {{ ragTestResult.message ||
                  (ragTestResult.ok === true
                    ? t('settings.rag.testSuccess')
                    : t('settings.rag.testFailHint')) }}
              </div>
            </div>
          </div>

          <!-- 检索参数 -->
          <div class="rag-section-head">
            <div class="block-title">{{ t('settings.rag.paramsTitle') }}</div>
            <span class="tiny dim">{{ t('settings.rag.paramsNote') }}</span>
          </div>
          <div class="eval-form">
            <div class="eval-field rag-field">
              <label class="eval-label">{{ t('settings.rag.topK') }}</label>
              <el-input-number
                v-model="ragForm.topK"
                :min="1"
                :max="10"
                :precision="0"
                controls-position="right"
                :disabled="ragSaving"
                style="width: 100%"
              />
            </div>
            <div class="eval-field rag-field">
              <label class="eval-label">{{ t('settings.rag.minScore') }}</label>
              <el-input-number
                v-model="ragForm.minScore"
                :min="0"
                :max="1"
                :step="0.05"
                :precision="2"
                controls-position="right"
                :disabled="ragSaving"
                style="width: 100%"
              />
            </div>
            <div class="eval-field rag-field">
              <label class="eval-label">{{ t('settings.rag.maxContextChars') }}</label>
              <el-input-number
                v-model="ragForm.maxContextChars"
                :min="500"
                :max="8000"
                :step="100"
                :precision="0"
                controls-position="right"
                :disabled="ragSaving"
                style="width: 100%"
              />
            </div>
            <div class="eval-field rag-field">
              <label class="eval-label">{{ t('settings.rag.corpusExamples') }}</label>
              <el-input-number
                v-model="ragForm.corpusExamples"
                :min="0"
                :max="10"
                :precision="0"
                controls-position="right"
                :disabled="ragSaving"
                style="width: 100%"
              />
            </div>
            <div class="eval-actions">
              <el-button type="primary" :loading="ragSaving" :disabled="ragSaving" @click="saveRagSettings">
                {{ t('settings.rag.save') }}
              </el-button>
            </div>
          </div>

          <!-- 索引统计 -->
          <div class="rag-section-head">
            <div class="block-title">{{ t('settings.rag.indexTitle') }}</div>
            <span class="tiny dim">
              {{ t('settings.rag.lastReindex', { time: rag.index.lastReindexAt ? formatTime(rag.index.lastReindexAt) : '—' }) }}
            </span>
          </div>
          <div class="notify-grid">
            <div class="notify-item">
              <div class="notify-label">{{ t('settings.rag.knowledgeReady') }}</div>
              <div class="notify-value num">
                {{ readyCountText(rag.index.knowledgeReady, rag.index.knowledgeTotal) }}
              </div>
              <div class="notify-sub tiny dim">
                {{ t('settings.rag.knowledgePendingFailed', { pending: rag.index.knowledgePending ?? '—', failed: rag.index.knowledgeFailed ?? '—' }) }}
              </div>
            </div>
            <div class="notify-item">
              <div class="notify-label">{{ t('settings.rag.corpusReady') }}</div>
              <div class="notify-value num">
                {{ readyCountText(rag.index.corpusReady, rag.index.corpusTotal) }}
              </div>
              <div class="notify-sub tiny dim">{{ t('settings.rag.corpusReadyNote') }}</div>
            </div>
            <div class="notify-item">
              <div class="notify-label">{{ t('settings.rag.vectorCapability') }}</div>
              <div class="notify-value">
                <span class="chip" :class="vectorState.type">{{ vectorState.label }}</span>
              </div>
              <div class="notify-sub tiny dim">{{ vectorState.note }}</div>
            </div>
          </div>

          <el-alert
            v-if="rag.index.vectorAvailable === false"
            class="rag-alert"
            type="warning"
            :closable="false"
            show-icon
            :title="t('settings.rag.vectorAlert')"
          />

          <div class="rag-actions">
            <el-button
              :loading="ragReindexing === 'knowledge'"
              :disabled="ragReindexing !== null"
              @click="runReindex('knowledge')"
            >
              {{ t('settings.rag.reindexKnowledge') }}
            </el-button>
            <el-button
              :loading="ragReindexing === 'corpus'"
              :disabled="ragReindexing !== null"
              @click="runReindex('corpus')"
            >
              {{ t('settings.rag.reindexCorpus') }}
            </el-button>
            <el-button
              type="warning"
              plain
              :loading="ragReindexing === 'all'"
              :disabled="ragReindexing !== null"
              @click="runReindex('all')"
            >
              {{ t('settings.rag.reindexAll') }}
            </el-button>
            <span class="tiny dim">{{ t('settings.rag.reindexNote') }}</span>
          </div>

          <!-- 效果评测 -->
          <div class="rag-section-head">
            <div class="block-title">{{ t('settings.rag.evalTitle') }}</div>
            <span class="tiny dim">{{ t('settings.rag.evalNote') }}</span>
          </div>
          <div class="eval-form">
            <div class="eval-field eval-size">
              <label class="eval-label">{{ t('settings.rag.evalSampleSize') }}</label>
              <el-input-number
                v-model="ragSampleSize"
                :min="5"
                :max="20"
                :precision="0"
                :disabled="ragEvaluating"
                controls-position="right"
                style="width: 100%"
              />
            </div>
            <div class="eval-actions">
              <el-button
                type="primary"
                :loading="ragEvaluating"
                :disabled="ragEvaluating"
                @click="runRagEvaluation"
              >
                <el-icon v-if="!ragEvaluating" class="el-icon--left"><DataAnalysis /></el-icon>
                {{ t('settings.rag.evalRun') }}
              </el-button>
              <span class="tiny dim">{{ t('settings.rag.evalCostHint') }}</span>
            </div>
          </div>

          <div v-if="ragEvaluating" class="eval-running tiny dim">
            <el-icon class="is-loading"><Loading /></el-icon>
            {{ t('settings.rag.evalRunning') }}
          </div>

          <div v-if="ragEvaluationError" class="eval-error">
            <el-icon :size="16"><WarningFilled /></el-icon>
            <div class="eval-error-text">
              <div class="strong">{{ t('settings.eval.incomplete') }}</div>
              <div class="tiny dim">{{ ragEvaluationError }}</div>
            </div>
            <el-button size="small" :disabled="ragEvaluating" @click="runRagEvaluation">{{ t('common.retry') }}</el-button>
          </div>

          <template v-if="ragEvaluation">
            <div class="eval-kpis">
              <div v-for="row in ragEvalRows" :key="row.key" class="eval-kpi">
                <div class="ab-label">{{ row.label }}</div>
                <div class="ab-value num">{{ formatRatioPercent(row.rag) }}</div>
                <div class="ab-sub tiny dim">
                  {{ t('settings.rag.baselineDelta', { baseline: formatRatioPercent(row.baseline), delta: row.deltaText }) }}
                </div>
              </div>
              <div class="eval-kpi">
                <div class="ab-label">{{ t('settings.eval.sampleSize') }}</div>
                <div class="ab-value num">{{ ragEvaluation.sampleSize ?? '—' }}</div>
                <div class="ab-sub tiny dim">{{ t('settings.rag.evalModel', { model: ragEvaluation.model || '—' }) }}</div>
              </div>
            </div>

            <div class="rag-eval-meta tiny dim">
              {{ t('settings.rag.evalFailed', { baseline: ragEvaluation.baseline.failed ?? '—', rag: ragEvaluation.rag.failed ?? '—' }) }}
              <span class="sep">·</span>
              {{ t('settings.rag.evalCorrect', { baseline: ragEvaluation.baseline.correct ?? '—', rag: ragEvaluation.rag.correct ?? '—' }) }}
            </div>

            <div class="rag-section-head">
              <div class="block-title">{{ t('settings.rag.evalCasesTitle') }}</div>
              <span class="tiny dim">{{ t('settings.rag.casesCount', { count: ragEvaluation.cases.length }) }}</span>
            </div>
            <div class="table-bleed">
              <el-table :data="ragEvaluation.cases" size="small" max-height="380">
                <el-table-column label="ID" width="76">
                  <template #default="{ row }">
                    <span class="mono">{{ row.id ?? '—' }}</span>
                  </template>
                </el-table-column>
                <el-table-column :label="t('settings.rag.colLabel')" width="96" align="center">
                  <template #default="{ row }">
                    <span class="chip" :class="evaluationLabelMeta(row.label).type">
                      {{ evaluationLabelMeta(row.label).label }}
                    </span>
                  </template>
                </el-table-column>
                <el-table-column :label="t('settings.rag.colBaselinePrediction')" width="96" align="center">
                  <template #default="{ row }">
                    <span class="chip" :class="evaluationLabelMeta(row.baselinePrediction).type">
                      {{ evaluationLabelMeta(row.baselinePrediction).label }}
                    </span>
                  </template>
                </el-table-column>
                <el-table-column :label="t('settings.rag.colRagPrediction')" width="96" align="center">
                  <template #default="{ row }">
                    <span class="chip" :class="evaluationLabelMeta(row.ragPrediction).type">
                      {{ evaluationLabelMeta(row.ragPrediction).label }}
                    </span>
                  </template>
                </el-table-column>
                <el-table-column :label="t('settings.rag.colConfidence')" width="166" align="right">
                  <template #default="{ row }">
                    <span class="num tiny">
                      {{ formatRatioPercent(row.baselineConfidence) }} →
                      {{ formatRatioPercent(row.ragConfidence) }}
                    </span>
                  </template>
                </el-table-column>
                <el-table-column :label="t('settings.rag.colRefs')" min-width="150">
                  <template #default="{ row }">
                    <span v-if="row.refs.length" class="tiny">{{ row.refs.join(t('settings.rag.refSeparator')) }}</span>
                    <span v-else class="dim">—</span>
                  </template>
                </el-table-column>
                <el-table-column :label="t('settings.rag.colError')" min-width="140">
                  <template #default="{ row }">
                    <el-tooltip
                      v-if="row.error"
                      :content="row.error"
                      placement="top"
                      :show-after="200"
                      :popper-style="{ maxWidth: '460px', wordBreak: 'break-all' }"
                    >
                      <span class="eval-reason err">{{ row.error }}</span>
                    </el-tooltip>
                    <span v-else class="dim">—</span>
                  </template>
                </el-table-column>
                <template #empty>
                  <div class="empty-block">
                    <div class="empty-title">{{ t('settings.rag.casesEmpty') }}</div>
                    <div class="tiny dim">{{ t('settings.rag.casesEmptyHint') }}</div>
                  </div>
                </template>
              </el-table>
            </div>
          </template>
          <div v-else-if="!ragEvaluating && !ragEvaluationError" class="eval-empty tiny dim">
            {{ t('settings.rag.evalEmpty') }}
          </div>

          <div class="hint tiny dim">
            {{ t('settings.rag.hintA') }}
            {{ t('settings.rag.hintB') }}
          </div>
        </template>
      </div>
    </section>

    <!-- ⑥ 生效范围 -->
    <section class="panel">
      <div class="panel-head">
        <div class="section-title">{{ t('settings.scope.title') }}</div>
      </div>
      <div class="panel-body">
        <ul class="scope-list">
          <li v-for="item in scope" :key="item.name" class="scope-item">
            <el-icon :size="15" :class="item.follow ? 'scope-yes' : 'scope-no'">
              <component :is="item.follow ? 'CircleCheckFilled' : 'RemoveFilled'" />
            </el-icon>
            <span class="scope-name">{{ item.name }}</span>
            <span class="tiny dim">{{ item.note }}</span>
          </li>
        </ul>

        <div class="code-block mono">
          <div class="code-title">{{ t('settings.scope.envTitle') }}</div>
          <pre># {{ t('settings.scope.envKeyComment') }}
DEEPSEEK_API_KEY=sk-xxxxxxxxxxxxxxxx

# {{ t('settings.scope.envOptionalComment') }}
# APP_SCREEN_PROVIDER=deepseek
# DEEPSEEK_SCREEN_MODEL=deepseek-flash</pre>
        </div>
      </div>
    </section>

    <!-- ⑦ 抽样与通知（ADMIN）：合规抽样比例 + Webhook 告警 -->
    <section v-if="canWrite" class="panel">
      <div class="panel-head">
        <div class="section-title">{{ t('settings.notify.title') }}</div>
        <div class="notify-head">
          <span class="tiny dim">{{ t('settings.notify.subtitle') }}</span>
          <el-button size="small" :loading="notifyLoading" @click="loadNotifications">{{ t('common.refresh') }}</el-button>
        </div>
      </div>
      <div v-loading="notifyLoading" class="panel-body">
        <!-- 接口未上线 / 无权限（403、404）时降级为可重试的提示，不影响页面其他设置 -->
        <div v-if="notifyError" class="notify-fallback">
          <el-icon :size="16"><WarningFilled /></el-icon>
          <div class="notify-fallback-text">
            <div class="strong">{{ t('settings.notify.unavailable') }}</div>
            <div class="tiny dim">{{ notifyError }}</div>
          </div>
          <el-button size="small" @click="loadNotifications">{{ t('common.retry') }}</el-button>
        </div>

        <template v-else-if="notify">
          <div class="notify-grid">
            <div class="notify-item">
              <div class="notify-label">{{ t('settings.notify.sampleRate') }}</div>
              <div class="notify-value num">{{ samplingRateText }}</div>
              <div class="notify-sub tiny dim">{{ samplingRateNote }}</div>
            </div>

            <div class="notify-item">
              <div class="notify-label">{{ t('settings.notify.webhook') }}</div>
              <div class="notify-value">
                <span class="chip" :class="webhookState.type">{{ webhookState.label }}</span>
              </div>
              <div class="notify-sub tiny dim">{{ webhookState.note }}</div>
            </div>

            <div class="notify-item">
              <div class="notify-label">{{ t('settings.notify.backlog') }}</div>
              <div class="notify-value num">{{ backlogThresholdText }}</div>
              <div class="notify-sub tiny dim">{{ t('settings.notify.backlogNote') }}</div>
            </div>
          </div>

          <div class="notify-events">
            <span class="notify-label">{{ t('settings.notify.events') }}</span>
            <div class="event-chips">
              <span
                v-for="event in notify.events"
                :key="event"
                class="chip"
                :class="notifyEventMeta(event).type"
              >{{ notifyEventMeta(event).label }}</span>
              <span v-if="!notify.events.length" class="tiny dim">{{ t('settings.notify.eventsEmpty') }}</span>
            </div>
          </div>

          <div class="notify-test">
            <el-button type="primary" :loading="notifyTesting" :disabled="!canTestWebhook" @click="testWebhook">
              <el-icon v-if="!notifyTesting" class="el-icon--left"><Connection /></el-icon>
              {{ t('settings.notify.sendTest') }}
            </el-button>
            <span class="tiny dim">{{ testHint }}</span>
          </div>

          <div v-if="notifyTestResult" class="test-result" :class="notifyTestResult.ok ? 'ok' : 'bad'">
            <el-icon :size="16">
              <component :is="notifyTestResult.ok ? 'CircleCheckFilled' : 'CircleCloseFilled'" />
            </el-icon>
            <div class="test-text">
              <div class="test-head">
                <b>{{ notifyTestResult.ok ? t('settings.notify.testOk') : t('settings.notify.testFail') }}</b>
                <span v-if="notifyTestResult.latencyMs != null" class="num dim">
                  {{ t('settings.test.latency', { ms: notifyTestResult.latencyMs }) }}
                </span>
              </div>
              <div class="test-msg">
                {{ notifyTestResult.message ||
                  (notifyTestResult.ok
                    ? t('settings.notify.testSuccessMsg')
                    : t('settings.notify.testFailMsg')) }}
              </div>
            </div>
          </div>

          <div class="hint tiny dim">
            {{ t('settings.notify.hintA') }}
            {{ t('settings.notify.hintB') }}
            {{ t('settings.notify.hintC') }}
          </div>
        </template>
      </div>
    </section>

    <!-- 新增 / 编辑供应商（ADMIN） -->
    <el-dialog
      v-model="providerDialogVisible"
      :title="providerEditing ? t('settings.provider.editTitle') : t('settings.provider.create')"
      width="600px"
      top="6vh"
      append-to-body
      class="provider-dialog"
      @closed="resetProviderForm"
    >
      <el-form
        ref="providerFormRef"
        :model="providerForm"
        :rules="providerRules"
        label-width="126px"
        @submit.prevent="submitProvider"
      >
        <el-form-item :label="t('settings.provider.formKey')" prop="key">
          <el-input
            v-if="!providerEditing"
            v-model="providerForm.key"
            :placeholder="t('settings.provider.formKeyPlaceholder')"
            autocomplete="off"
          />
          <el-input v-else :model-value="providerEditing.key" class="mono" disabled />
        </el-form-item>

        <el-form-item :label="t('common.name')" prop="label">
          <el-input
            v-model="providerForm.label"
            maxlength="40"
            :placeholder="t('settings.provider.formLabelPlaceholder')"
          />
        </el-form-item>

        <el-form-item label="Base URL" prop="baseUrl">
          <el-input v-model="providerForm.baseUrl" placeholder="https://api.example.com" />
        </el-form-item>

        <el-form-item label="Chat Path" prop="chatPath">
          <el-input v-model="providerForm.chatPath" placeholder="/v1/chat/completions" />
        </el-form-item>

        <el-form-item :label="t('settings.provider.defaultModel')" prop="defaultModel">
          <el-input
            v-model="providerForm.defaultModel"
            :placeholder="t('settings.provider.formDefaultModelPlaceholder')"
          />
        </el-form-item>

        <el-form-item :label="t('settings.provider.formModels')">
          <el-select
            v-model="providerForm.models"
            multiple
            filterable
            allow-create
            default-first-option
            :placeholder="t('settings.provider.formModelsPlaceholder')"
            style="width: 100%"
          />
          <div class="tiny dim form-hint">{{ t('settings.provider.formModelsHint') }}</div>
        </el-form-item>

        <el-form-item :label="t('settings.provider.pricePer1k')">
          <div class="price-fields">
            <div class="price-field">
              <span class="tiny dim">{{ t('settings.provider.formInputPrice') }}</span>
              <el-input-number
                v-model="providerForm.inputPricePer1k"
                :min="0"
                :precision="6"
                :step="0.0001"
                controls-position="right"
                style="width: 100%"
              />
            </div>
            <div class="price-field">
              <span class="tiny dim">{{ t('settings.provider.formOutputPrice') }}</span>
              <el-input-number
                v-model="providerForm.outputPricePer1k"
                :min="0"
                :precision="6"
                :step="0.0001"
                controls-position="right"
                style="width: 100%"
              />
            </div>
          </div>
          <div class="tiny dim form-hint">
            {{ t('settings.provider.formPriceHint') }}
          </div>
        </el-form-item>

        <el-form-item :label="t('settings.provider.formMaxTokens')">
          <el-input-number
            v-model="providerForm.maxTokens"
            :min="0"
            :step="256"
            :precision="0"
            controls-position="right"
            :placeholder="t('settings.provider.formMaxTokensPlaceholder')"
            style="width: 100%"
          />
        </el-form-item>

        <el-form-item :label="t('settings.provider.formJsonMode')">
          <el-switch v-model="providerForm.jsonMode" />
          <span class="tiny dim form-inline-hint">{{ t('settings.provider.formJsonModeHint') }}</span>
        </el-form-item>

        <el-form-item :label="t('settings.provider.formEnabled')">
          <el-switch v-model="providerForm.enabled" />
          <span class="tiny dim form-inline-hint">{{ t('settings.provider.formEnabledHint') }}</span>
        </el-form-item>

        <el-form-item label="API Key">
          <el-input
            v-model="providerForm.apiKey"
            type="password"
            show-password
            autocomplete="new-password"
            :placeholder="providerEditing
              ? t('settings.provider.formApiKeyKeep', { masked: providerEditing.apiKeyMasked || t('settings.state.notConfigured') })
              : t('settings.provider.formApiKeyOptional')"
          />
          <div class="tiny dim form-hint">{{ t('settings.provider.formApiKeyHint') }}</div>
        </el-form-item>

        <el-alert
          v-if="providerEditing?.builtIn"
          type="info"
          :closable="false"
          show-icon
          :title="t('settings.provider.formBuiltInAlert')"
        />
      </el-form>
      <template #footer>
        <el-button @click="providerDialogVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="providerSaving" @click="submitProvider">
          {{ providerEditing ? t('settings.provider.saveEdit') : t('settings.provider.createSubmit') }}
        </el-button>
      </template>
    </el-dialog>

    <!-- 供应商连通性测试（可选模型） -->
    <el-dialog
      v-model="providerTestVisible"
      :title="t('settings.providerTest.title')"
      width="520px"
      append-to-body
      @closed="resetProviderTest"
    >
      <div v-if="providerTestTarget" class="test-target">
        <div class="test-target-name">
          {{ providerTestTarget.label || providerTestTarget.key }}
          <span v-if="providerTestTarget.builtIn" class="chip info">{{ t('settings.provider.builtIn') }}</span>
          <span v-if="providerTestTarget.enabled === false" class="chip warn">{{ t('settings.providerTest.disabledChip') }}</span>
        </div>
        <div class="mono tiny dim">
          {{ providerTestTarget.baseUrl }}{{ providerTestTarget.chatPath }}
        </div>
      </div>
      <el-form label-width="86px" @submit.prevent="runProviderTest">
        <el-form-item :label="t('settings.providerTest.model')">
          <el-input
            v-model="providerTestModel"
            clearable
            :placeholder="t('settings.providerTest.modelPlaceholder', { model: providerTestTarget?.defaultModel || '—' })"
            @keyup.enter="runProviderTest"
          />
        </el-form-item>
      </el-form>
      <div v-if="providerTestResult" class="test-result" :class="providerTestResult.ok ? 'ok' : 'bad'">
        <el-icon :size="16">
          <component :is="providerTestResult.ok ? 'CircleCheckFilled' : 'CircleCloseFilled'" />
        </el-icon>
        <div class="test-text">
          <div class="test-head">
            <b>{{ providerTestResult.ok ? t('settings.test.ok') : t('settings.test.fail') }}</b>
            <span v-if="providerTestResult.model" class="mono dim">{{ providerTestResult.model }}</span>
            <span v-if="providerTestResult.latencyMs != null" class="num dim">
              {{ t('settings.test.latency', { ms: providerTestResult.latencyMs }) }}
            </span>
          </div>
          <div class="test-msg">
            {{ providerTestResult.ok
              ? (providerTestResult.reply || providerTestResult.message || t('settings.test.emptyReply'))
              : (providerTestResult.message || t('settings.providerTest.failHint')) }}
          </div>
        </div>
      </div>
      <template #footer>
        <el-button @click="providerTestVisible = false">{{ t('common.close') }}</el-button>
        <el-button type="primary" :loading="providerTestRunning" @click="runProviderTest">
          {{ t('settings.providerTest.send') }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../api'
import auth from '../auth'
import { useI18n } from '../i18n'
import { formatTime, notifyEventMeta } from '../constants'
import { formatSamplingRate } from '../utils/notifications'
import { formatPricePer1k, isProviderEnabled } from '../utils/providers'
import { useElementWidth } from '../utils/elementWidth'
import {
  evaluationLabelMeta,
  formatAbPercent,
  formatLatencyMs,
  formatRatioPercent,
  normalizeScreenModel
} from '../utils/evaluation'

// 文案统一走 i18n：模板 / computed / 校验规则内调用 t()，切换语言后自动重渲染
const { t } = useI18n()

// 系统设置写操作仅 ADMIN：非管理员只读查看，后端仍会独立校验
const canWrite = computed(() => auth.hasRole('ADMIN'))

const view = ref(null)
const draft = reactive({ providerId: '', model: '' })
const saving = ref(false)
/** 正在测试的目标：'active' | 'draft' | null */
const testing = ref(null)
const testResult = ref(null)

// 抽样与通知（仅 ADMIN）：GET /api/settings/notifications 为 ADMIN 接口，
// 后端未上线（404）或无权限（403）时降级为卡片内提示，不弹全局错误。
const notify = ref(null)
const notifyLoading = ref(false)
const notifyError = ref('')
const notifyTesting = ref(false)
const notifyTestResult = ref(null)

// AI 供应商注册表（仅 ADMIN）：内置项由后端配置落库，自定义项可增删改
const providers = ref([])
/* 供应商表窄屏收起「价格 / 1K」，避免固定操作列压住密钥/状态。
   阈值取 990：1280 笔记本（表格约 958px）下价格让位给「接入地址 / 默认模型」，
   这两列才是选供应商时真正要读的信息。 */
const providerTableWrap = ref(null)
const providerTableWidth = useElementWidth(providerTableWrap)
const compactProviderTable = computed(() => providerTableWidth.value > 0 && providerTableWidth.value < 990)
const providersLoading = ref(false)
const providersError = ref('')
const providerDialogVisible = ref(false)
const providerEditing = ref(null)
const providerSaving = ref(false)
const providerFormRef = ref(null)
const providerForm = reactive(emptyProviderForm())
const providerTestVisible = ref(false)
const providerTestTarget = ref(null)
const providerTestModel = ref('')
const providerTestRunning = ref(false)
const providerTestResult = ref(null)

// A/B 与模型评测（仅 ADMIN）：ab 随 screen-model 视图下发（只读）；评测调用真实模型
const evalForm = reactive({ providerId: '', model: '', sampleSize: 10 })
const evaluating = ref(false)
const evaluation = ref(null)
const evaluationError = ref('')

// RAG 检索增强（仅 ADMIN）：embedding 复用 AI 供应商注册表；索引重建与评测均为 ADMIN 接口
const rag = ref(null)
const ragLoading = ref(false)
const ragError = ref('')
const ragSaving = ref(false)
const ragEnabledSaving = ref(false)
const ragTesting = ref(false)
const ragTestResult = ref(null)
/** 正在重建的范围：'knowledge' | 'corpus' | 'all' | null */
const ragReindexing = ref(null)
const ragEvaluating = ref(false)
const ragEvaluation = ref(null)
const ragEvaluationError = ref('')
const ragSampleSize = ref(10)

/** 与 application.yml 默认值一致：仅作编辑控件初值，保存时整体回写后端 */
const RAG_DEFAULT_RETRIEVAL = { topK: 4, minScore: 0.25, maxContextChars: 2400, corpusExamples: 5 }
const ragForm = reactive({ enabled: false, ...RAG_DEFAULT_RETRIEVAL })

const currentProvider = computed(
  () => (view.value?.providers || []).find((p) => p.id === draft.providerId) || null
)

const dirty = computed(
  () => !!view.value && (draft.providerId !== view.value.providerId || draft.model !== view.value.model)
)

/** 生效范围：文案随语言切换（t 放在 computed 里，避免顶层固化语言） */
const scope = computed(() => [
  { name: t('settings.scope.aiScreening'), follow: true, note: t('settings.scope.aiScreeningNote') },
  { name: t('settings.scope.mining'), follow: true, note: t('settings.scope.miningNote') },
  {
    name: t('settings.scope.transcription'),
    follow: false,
    note: t('settings.scope.transcriptionNote')
  },
  { name: t('settings.scope.dfa'), follow: false, note: t('settings.scope.dfaNote') }
])

async function load() {
  try {
    const data = await api.screenModel()
    view.value = data
    draft.providerId = data.providerId
    draft.model = data.model
  } catch {
    // 请求层已统一提示
  }
}

function pickProvider(p) {
  draft.providerId = p.id
  draft.model = p.defaultModel || p.models?.[0] || ''
}

async function save() {
  saving.value = true
  testResult.value = null
  try {
    const data = await api.updateScreenModel({ providerId: draft.providerId, model: draft.model })
    view.value = normalizeScreenModel(data)
    draft.providerId = data.providerId
    draft.model = data.model
    ElMessage.success(
      t('settings.active.switched', { provider: data.providerLabel, model: data.model })
    )
  } catch {
    // 请求层已统一提示
  } finally {
    saving.value = false
  }
}

async function runTest(target, payload) {
  testing.value = target
  testResult.value = null
  try {
    testResult.value = await api.testScreenModel(payload)
  } catch {
    // 请求层已统一提示
  } finally {
    testing.value = null
  }
}

/** 测「当前生效」的模型 */
function testActive() {
  if (!view.value) return
  runTest('active', { providerId: view.value.providerId, model: view.value.model })
}

/** 测「待保存」的选择，便于先验证再切换 */
function testDraft() {
  if (!draft.providerId) return
  runTest('draft', { providerId: draft.providerId, model: draft.model })
}

/* ---------- 抽样与通知（ADMIN） ---------- */

const samplingRateText = computed(() => formatSamplingRate(notify.value?.samplingRate))

const samplingRateNote = computed(() =>
  notify.value?.samplingRate != null
    ? t('settings.notify.sampleRateNote')
    : t('settings.notify.sampleRateMissing')
)

/** Webhook 状态：接口字段缺失时显示「未知」，不臆造启用状态 */
const webhookState = computed(() => {
  const data = notify.value
  if (!data) return { label: '—', type: 'info', note: '' }
  if (data.enabled === false) {
    return {
      label: t('settings.state.disabled'),
      type: 'info',
      note: t('settings.notify.disabledNote')
    }
  }
  if (data.enabled === true) {
    if (data.urlConfigured === false) {
      return {
        label: t('settings.state.urlMissing'),
        type: 'warn',
        note: t('settings.notify.urlMissingNote')
      }
    }
    if (data.urlConfigured === true) {
      return {
        label: t('settings.state.enabled'),
        type: 'ok',
        note: t('settings.notify.enabledNote')
      }
    }
    return {
      label: t('settings.state.enabled'),
      type: 'ok',
      note: t('settings.notify.urlUnknownNote')
    }
  }
  if (data.urlConfigured === true) {
    return {
      label: t('settings.state.configured'),
      type: 'ok',
      note: t('settings.notify.enabledUnknownNote')
    }
  }
  return {
    label: t('common.unknown'),
    type: 'info',
    note: t('settings.notify.unknownNote')
  }
})

const backlogThresholdText = computed(() => {
  const threshold = notify.value?.backlogThreshold
  return threshold === null || threshold === undefined
    ? '—'
    : t('settings.notify.backlogValue', { count: threshold })
})

/** 仅当能确认未启用 / URL 未配置时禁用测试，字段缺失时仍允许尝试（后端为准） */
const canTestWebhook = computed(() => {
  const data = notify.value
  if (!data || notifyTesting.value) return false
  return data.enabled !== false && data.urlConfigured !== false
})

const testHint = computed(() => {
  const data = notify.value
  if (!data) return ''
  if (data.enabled === false) return t('settings.notify.hintDisabled')
  if (data.urlConfigured === false) return t('settings.notify.hintUrlMissing')
  return t('settings.notify.hintTest')
})

/** 接口错误文案：区分 403/404，避免把后端未上线误报成普通失败（通知 / 供应商共用） */
function adminErrorMessage(error, fallback) {
  const status = error?.status
  if (status === 403) return t('settings.error.forbidden')
  if (status === 404) return t('settings.error.notFound')
  return error?.message || fallback
}

async function loadNotifications() {
  if (!canWrite.value) return
  notifyLoading.value = true
  notifyError.value = ''
  try {
    notify.value = await api.notificationSettings()
  } catch (e) {
    notify.value = null
    notifyError.value = adminErrorMessage(e, t('settings.notify.loadFailed'))
  } finally {
    notifyLoading.value = false
  }
}

async function testWebhook() {
  if (!canTestWebhook.value) return
  notifyTesting.value = true
  notifyTestResult.value = null
  try {
    notifyTestResult.value = await api.testNotification()
  } catch (e) {
    notifyTestResult.value = {
      ok: false,
      latencyMs: null,
      message: adminErrorMessage(e, t('settings.notify.testFailedMsg'))
    }
  } finally {
    notifyTesting.value = false
  }
}

/* ---------- AI 供应商（ADMIN） ---------- */

function emptyProviderForm() {
  return {
    key: '',
    label: '',
    baseUrl: '',
    chatPath: '/v1/chat/completions',
    defaultModel: '',
    models: [],
    jsonMode: true,
    maxTokens: null,
    inputPricePer1k: 0,
    outputPricePer1k: 0,
    apiKey: '',
    enabled: true
  }
}

/** 校验规则：文案随语言切换，用 computed 保证换语言后重新校验时拿到新文案 */
const providerRules = computed(() => ({
  key: [{ validator: validateProviderKey, trigger: 'blur' }],
  label: [
    { required: true, message: t('settings.provider.ruleLabelRequired'), trigger: 'blur' }
  ],
  baseUrl: [
    { required: true, message: t('settings.provider.ruleBaseUrlRequired'), trigger: 'blur' },
    {
      pattern: /^https?:\/\/\S+$/i,
      message: t('settings.provider.ruleBaseUrlPattern'),
      trigger: 'blur'
    }
  ],
  defaultModel: [
    {
      required: true,
      message: t('settings.provider.ruleDefaultModelRequired'),
      trigger: 'blur'
    }
  ]
}))

/** 唯一标识只在新建时校验（编辑时 key 不可改，兼容后端已有命名） */
function validateProviderKey(rule, value, callback) {
  if (providerEditing.value) return callback()
  const text = String(value || '').trim()
  if (!text) return callback(new Error(t('settings.provider.ruleKeyRequired')))
  if (!/^[a-z0-9][a-z0-9._-]{0,63}$/.test(text)) {
    return callback(new Error(t('settings.provider.ruleKeyPattern')))
  }
  callback()
}

const currentProviderKey = computed(() => String(view.value?.providerId || ''))

/** 该供应商是否是「当前生效」的供应商 */
function providerIsActive(row) {
  if (!currentProviderKey.value || !row?.key) return false
  return row.key.toLowerCase() === currentProviderKey.value.toLowerCase()
}

/** 切换模型卡片：停用的供应商不可选（列表接口不可用时按可用处理，保持原行为） */
function isProviderDisabled(key) {
  if (!key) return false
  const found = providers.value.find(
    (p) => String(p.key || '').toLowerCase() === String(key).toLowerCase()
  )
  return !!found && !isProviderEnabled(found)
}

/** 密钥状态：只展示后端掩码，不接触明文 */
function providerKeyChip(row) {
  const masked = row?.apiKeyMasked || ''
  if (row?.apiKeyConfigured === true) {
    return { label: masked || t('settings.state.configured'), type: 'ok' }
  }
  if (row?.apiKeyConfigured === false) {
    return { label: t('settings.state.notConfigured'), type: 'warn' }
  }
  return { label: masked || t('common.unknown'), type: 'info' }
}

async function loadProviders() {
  if (!canWrite.value) return
  providersLoading.value = true
  providersError.value = ''
  try {
    providers.value = await api.listProviders()
  } catch (e) {
    providers.value = []
    providersError.value = adminErrorMessage(e, t('settings.provider.loadFailed'))
  } finally {
    providersLoading.value = false
  }
}

/** 供应商增删改 / 测试后同时刷新注册表与「当前生效」视图，避免切换区显示旧数据 */
async function refreshAll() {
  await Promise.all([load(), loadProviders()])
}

function resetProviderForm() {
  providerEditing.value = null
  Object.assign(providerForm, emptyProviderForm())
  providerFormRef.value?.clearValidate?.()
}

function openProviderCreate() {
  resetProviderForm()
  providerDialogVisible.value = true
}

function openProviderEdit(row) {
  providerEditing.value = row
  Object.assign(providerForm, {
    key: row.key || '',
    label: row.label || '',
    baseUrl: row.baseUrl || '',
    chatPath: row.chatPath || '/v1/chat/completions',
    defaultModel: row.defaultModel || '',
    models: [...(row.models || [])],
    jsonMode: row.jsonMode !== false,
    maxTokens: row.maxTokens ?? null,
    inputPricePer1k: row.inputPricePer1k ?? 0,
    outputPricePer1k: row.outputPricePer1k ?? 0,
    apiKey: '',
    enabled: row.enabled !== false
  })
  providerDialogVisible.value = true
}

async function submitProvider() {
  if (!providerFormRef.value) return
  try {
    await providerFormRef.value.validate()
  } catch {
    return
  }
  const editing = providerEditing.value
  providerSaving.value = true
  try {
    const payload = {
      label: providerForm.label.trim(),
      baseUrl: providerForm.baseUrl.trim(),
      chatPath: providerForm.chatPath.trim() || undefined,
      defaultModel: providerForm.defaultModel.trim(),
      models: (providerForm.models || []).map((m) => String(m).trim()).filter(Boolean),
      jsonMode: !!providerForm.jsonMode,
      maxTokens:
        providerForm.maxTokens === null || providerForm.maxTokens === undefined
          ? null
          : Number(providerForm.maxTokens),
      inputPricePer1k: Number(providerForm.inputPricePer1k) || 0,
      outputPricePer1k: Number(providerForm.outputPricePer1k) || 0,
      enabled: !!providerForm.enabled
    }
    // 密钥留空表示保持原密钥（编辑）或不配置（新建）
    const apiKey = providerForm.apiKey.trim()
    if (apiKey) payload.apiKey = apiKey

    if (editing) {
      await api.updateProvider(editing.key, payload)
      ElMessage.success(t('settings.provider.updated', { label: payload.label }))
    } else {
      payload.key = providerForm.key.trim()
      await api.createProvider(payload)
      ElMessage.success(t('settings.provider.created', { label: payload.label }))
    }
    providerDialogVisible.value = false
    await refreshAll()
  } catch {
    // 请求层已统一提示（重复 key / 当前生效不可删等由后端校验）
  } finally {
    providerSaving.value = false
  }
}

async function removeProvider(row) {
  try {
    await api.deleteProvider(row.key)
    ElMessage.success(t('settings.provider.deleted', { label: row.label || row.key }))
    await refreshAll()
  } catch {
    // 请求层已统一提示（仅自定义、非当前生效可删）
  }
}

function openProviderTest(row) {
  providerTestTarget.value = row
  providerTestModel.value = ''
  providerTestResult.value = null
  providerTestVisible.value = true
}

function resetProviderTest() {
  providerTestTarget.value = null
  providerTestModel.value = ''
  providerTestResult.value = null
}

async function runProviderTest() {
  const target = providerTestTarget.value
  if (!target || providerTestRunning.value) return
  providerTestRunning.value = true
  providerTestResult.value = null
  try {
    // 空模型由后端回退到供应商默认模型
    providerTestResult.value = await api.testProvider(target.key, providerTestModel.value.trim())
  } catch (e) {
    providerTestResult.value = {
      ok: false,
      latencyMs: null,
      model: '',
      providerLabel: '',
      reply: '',
      message: adminErrorMessage(e, t('settings.provider.testFailed'))
    }
  } finally {
    providerTestRunning.value = false
  }
}

/* ---------- A/B 与模型评测（ADMIN） ---------- */

const abConfig = computed(() => view.value?.ab || null)

/** A/B 状态：接口未返回 ab 时显示占位，不臆造启用状态 */
const abState = computed(() => {
  if (!view.value) return { label: t('common.loading'), type: 'info', note: '' }
  const ab = abConfig.value
  if (!ab) {
    return {
      label: t('settings.ab.stateNotConfigured'),
      type: 'info',
      note: t('settings.ab.noteNoConfig')
    }
  }
  if (ab.enabled === false) {
    return {
      label: t('settings.state.disabled'),
      type: 'info',
      note: t('settings.ab.noteDisabled')
    }
  }
  if (ab.enabled === true) {
    return {
      label: t('settings.state.enabled'),
      type: 'ok',
      note:
        ab.percent == null
          ? t('settings.ab.notePercentMissing')
          : t('settings.ab.notePercent', { percent: formatAbPercent(ab.percent) })
    }
  }
  return {
    label: t('settings.ab.stateUnknown'),
    type: 'warn',
    note: t('settings.ab.noteUnknown')
  }
})

/** B 组供应商：优先用注册表里的展示名，缺失时回退原始 key */
const abProviderLabel = computed(() => {
  const id = abConfig.value?.providerId
  if (!id) return '—'
  const found = providers.value.find(
    (p) => String(p.key || '').toLowerCase() === id.toLowerCase()
  )
  return found?.label || id
})

/** 评测供应商候选：启用中的注册表供应商；注册表不可用时回退当前生效供应商 */
const evalProviderOptions = computed(() => {
  const list = providers.value
    .filter((p) => p.key && isProviderEnabled(p))
    .map((p) => ({ value: p.key, label: p.label || p.key }))
  if (list.length) return list
  const current = view.value
  if (current?.providerId) {
    return [{ value: current.providerId, label: current.providerLabel || current.providerId }]
  }
  return []
})

/** 当前选中供应商的候选模型：注册表优先，其次 screen-model 视图 */
const evalModelOptions = computed(() => {
  const selected = String(evalForm.providerId || '').toLowerCase()
  if (!selected) return []
  const registered = providers.value.find(
    (p) => String(p.key || '').toLowerCase() === selected
  )
  if (registered?.models?.length) return registered.models
  const current = view.value?.providers?.find(
    (p) => String(p.id || '').toLowerCase() === selected
  )
  return current?.models || []
})

const evaluatedText = computed(() => {
  const data = evaluation.value
  if (!data) return '—'
  const evaluated = data.evaluated == null ? '—' : data.evaluated
  return data.total == null ? `${evaluated}` : `${evaluated} / ${data.total}`
})

/* ---------- RAG 检索增强（ADMIN） ---------- */

/** 向量能力状态：字段缺失显示未知，不臆造可用性 */
const vectorState = computed(() => {
  const available = rag.value?.index?.vectorAvailable
  if (available === true) {
    return {
      label: t('settings.state.available'),
      type: 'ok',
      note: t('settings.rag.vectorAvailableNote')
    }
  }
  if (available === false) {
    return {
      label: t('settings.state.unavailable'),
      type: 'warn',
      note: t('settings.rag.vectorUnavailableNote')
    }
  }
  return {
    label: t('common.unknown'),
    type: 'info',
    note: t('settings.rag.vectorUnknownNote')
  }
})

/** 评测三项指标：RAG 为主值，附基线值与 delta（delta 缺失时由两者差值推导） */
const ragEvalRows = computed(() => {
  const data = ragEvaluation.value
  if (!data) return []
  const rows = [
    { key: 'accuracy', label: 'Accuracy', baseline: data.baseline.accuracy, rag: data.rag.accuracy, delta: data.delta.accuracy },
    { key: 'precision', label: 'Precision', baseline: data.baseline.precision, rag: data.rag.precision, delta: data.delta.precision },
    { key: 'recall', label: 'Recall', baseline: data.baseline.recall, rag: data.rag.recall, delta: data.delta.recall }
  ]
  return rows.map((row) => ({
    ...row,
    deltaText: formatDeltaPercent(
      row.delta ?? (row.baseline != null && row.rag != null ? row.rag - row.baseline : null)
    )
  }))
})

/** 同步后端返回的归一化设置到编辑控件（缺失字段回退契约默认值，便于继续保存） */
function syncRagForm(next) {
  const data = next || rag.value
  if (!data) return
  const retrieval = data.retrieval || {}
  ragForm.enabled = data.enabled === true
  ragForm.topK = retrieval.topK ?? RAG_DEFAULT_RETRIEVAL.topK
  ragForm.minScore = retrieval.minScore ?? RAG_DEFAULT_RETRIEVAL.minScore
  ragForm.maxContextChars = retrieval.maxContextChars ?? RAG_DEFAULT_RETRIEVAL.maxContextChars
  ragForm.corpusExamples = retrieval.corpusExamples ?? RAG_DEFAULT_RETRIEVAL.corpusExamples
}

function ragPayload(enabled = ragForm.enabled) {
  return {
    enabled: !!enabled,
    topK: clampRagNumber(ragForm.topK, 1, 10, RAG_DEFAULT_RETRIEVAL.topK),
    minScore: clampRagNumber(ragForm.minScore, 0, 1, RAG_DEFAULT_RETRIEVAL.minScore),
    maxContextChars: clampRagNumber(
      ragForm.maxContextChars,
      500,
      8000,
      RAG_DEFAULT_RETRIEVAL.maxContextChars
    ),
    corpusExamples: clampRagNumber(
      ragForm.corpusExamples,
      0,
      10,
      RAG_DEFAULT_RETRIEVAL.corpusExamples
    )
  }
}

/** 控件清空 / 非法输入时回退默认值并夹取到契约范围，避免把 NaN 发给后端 */
function clampRagNumber(value, min, max, fallback) {
  const num = Number(value)
  if (!Number.isFinite(num)) return fallback
  return Math.min(max, Math.max(min, num))
}

async function loadRag() {
  if (!canWrite.value) return
  ragLoading.value = true
  ragError.value = ''
  try {
    rag.value = await api.getRagSettings()
    syncRagForm(rag.value)
  } catch (e) {
    // 请求层 silent：卡内展示 403/404 降级提示
    rag.value = null
    ragError.value = adminErrorMessage(e, t('settings.rag.loadFailed'))
  } finally {
    ragLoading.value = false
  }
}

async function saveRagSettings() {
  if (ragSaving.value) return
  ragSaving.value = true
  try {
    rag.value = await api.updateRagSettings(ragPayload())
    syncRagForm(rag.value)
    ElMessage.success(t('settings.rag.saved'))
  } catch {
    // 请求层已统一提示
  } finally {
    ragSaving.value = false
  }
}

/** 总开关：切换即 PUT 保存，失败时回滚开关位置 */
async function saveRagEnabled(value) {
  if (ragEnabledSaving.value) return
  ragEnabledSaving.value = true
  try {
    rag.value = await api.updateRagSettings(ragPayload(value))
    syncRagForm(rag.value)
    ElMessage.success(
      value ? t('settings.rag.enabledToast') : t('settings.rag.disabledToast')
    )
  } catch {
    ragForm.enabled = rag.value?.enabled === true
  } finally {
    ragEnabledSaving.value = false
  }
}

async function testRagEmbedding() {
  if (ragTesting.value) return
  ragTesting.value = true
  ragTestResult.value = null
  try {
    ragTestResult.value = await api.testRagEmbedding()
  } catch (e) {
    ragTestResult.value = {
      ok: false,
      providerLabel: '',
      model: '',
      dimensions: null,
      latencyMs: null,
      message: adminErrorMessage(e, t('settings.rag.testFailed'))
    }
  } finally {
    ragTesting.value = false
  }
}

function reindexLabel(scope) {
  if (scope === 'knowledge') return t('settings.rag.scopeKnowledge')
  if (scope === 'corpus') return t('settings.rag.scopeCorpus')
  return t('settings.rag.scopeAll')
}

async function runReindex(scope) {
  if (ragReindexing.value !== null) return
  if (scope === 'all') {
    try {
      await ElMessageBox.confirm(
        t('settings.rag.reindexConfirmMessage'),
        t('settings.rag.reindexConfirmTitle'),
        {
          type: 'warning',
          confirmButtonText: t('settings.rag.reindexConfirmStart'),
          cancelButtonText: t('common.cancel')
        }
      )
    } catch {
      return
    }
  }
  ragReindexing.value = scope
  try {
    const result = await api.reindexRag(scope)
    const counts = []
    if (result.queued !== null) counts.push(t('settings.rag.queued', { count: result.queued }))
    if (result.skipped !== null) {
      counts.push(t('settings.rag.skipped', { count: result.skipped }))
    }
    ElMessage.success(
      result.message ||
        t('settings.rag.reindexSubmitted', {
          scope: reindexLabel(scope),
          detail: counts.length
            ? t('settings.rag.reindexCountsDetail', {
                counts: counts.join(t('settings.rag.countSeparator'))
              })
            : ''
        })
    )
  } catch {
    // 请求层已统一提示
  } finally {
    ragReindexing.value = null
  }
}

async function runRagEvaluation() {
  if (ragEvaluating.value) return
  const sampleSize = Math.min(20, Math.max(5, Math.round(Number(ragSampleSize.value) || 10)))
  ragSampleSize.value = sampleSize
  ragEvaluating.value = true
  ragEvaluationError.value = ''
  try {
    ragEvaluation.value = await api.evaluateRag(sampleSize)
  } catch (e) {
    // 请求层 silent：保留上一次结果，仅在卡内提示失败原因
    ragEvaluationError.value = adminErrorMessage(e, t('settings.rag.evalFailedMsg'))
  } finally {
    ragEvaluating.value = false
  }
}

/** delta 以比例返回（如 0.1）：展示为 +10.0pp */
function formatDeltaPercent(value) {
  if (value === null || value === undefined || value === '') return '—'
  const num = Number(value)
  if (!Number.isFinite(num)) return '—'
  const pp = num * 100
  const sign = pp > 0 ? '+' : ''
  return `${sign}${Number(pp.toFixed(1))}pp`
}

/** 已就绪 / 总数：任一缺失时显示占位，不臆造 0 */
function readyCountText(ready, total) {
  if (ready === null && total === null) return '—'
  return `${ready ?? '—'} / ${total ?? '—'}`
}

/** Embedding API Key 状态：只展示后端是否已配置，不接触明文 */
function embeddingKeyChip(configured) {
  if (configured === true) return { label: t('settings.state.configured'), type: 'ok' }
  if (configured === false) return { label: t('settings.state.notConfigured'), type: 'warn' }
  return { label: t('common.unknown'), type: 'info' }
}

/** 配置来源：与「当前 AI 复筛模型」一致的中文展示，未知值原样回退 */
function ragSourceLabel(source) {
  const text = String(source || '')
  if (!text) return ''
  const key = text.toLowerCase()
  if (key === 'defaults' || key === 'default') return t('settings.source.configDefault')
  if (key === 'database' || key === 'db') return t('settings.source.uiSaved')
  return text
}

function onEvalProviderChange() {
  if (!evalModelOptions.value.includes(evalForm.model)) evalForm.model = ''
}

/** byLabel 行准确率：无样本时返回 null（显示占位） */
function labelAccuracy(row) {
  const total = Number(row?.total) || 0
  if (total <= 0) return null
  return (Number(row?.correct) || 0) / total
}

/** 评测接口错误：403/404 给出可读降级，其余保留后端 message（如配额不足） */
function evalErrorMessage(error) {
  const status = error?.status
  if (status === 403) return t('settings.eval.errorForbidden')
  if (status === 404) return t('settings.eval.errorNotFound')
  return error?.message || t('settings.eval.errorGeneric')
}

async function runEvaluation() {
  if (evaluating.value) return
  const providerId = evalForm.providerId
  if (!providerId) {
    ElMessage.warning(t('settings.eval.selectProviderFirst'))
    return
  }
  const sampleSize = Math.min(20, Math.max(1, Math.round(Number(evalForm.sampleSize) || 10)))
  evalForm.sampleSize = sampleSize
  const payload = { providerId, sampleSize }
  const model = evalForm.model.trim()
  if (model) payload.model = model

  evaluating.value = true
  evaluationError.value = ''
  try {
    evaluation.value = await api.evaluateScreenModel(payload)
  } catch (e) {
    // 请求层 silent：保留上一次结果，仅在卡内提示失败原因
    evaluationError.value = evalErrorMessage(e)
  } finally {
    evaluating.value = false
  }
}

// 供应商数据晚于页面加载：自动选中当前生效供应商并清理失效模型
watch(evalProviderOptions, () => {
  const options = evalProviderOptions.value
  if (!options.length) return
  if (!options.some((opt) => opt.value === evalForm.providerId)) {
    const current = options.find((opt) => opt.value === view.value?.providerId)
    evalForm.providerId = (current || options[0]).value
  }
  if (evalForm.model && !evalModelOptions.value.includes(evalForm.model)) {
    evalForm.model = ''
  }
})

onMounted(() => {
  load()
  // 管理员专属接口：非管理员不请求、不渲染对应卡片
  if (canWrite.value) {
    loadNotifications()
    loadProviders()
    loadRag()
  }
})
</script>

<style scoped>
/* 当前生效卡片 */
.active-card {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 14px 16px;
  border: 1px solid var(--border);
  border-radius: var(--r-md);
  background: linear-gradient(180deg, #fbfcff 0%, #f7f9fe 100%);
}
.active-left {
  display: flex;
  align-items: center;
  gap: 13px;
  min-width: 0;
}
.active-mark {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 40px;
  height: 40px;
  flex: none;
  border-radius: var(--r-md);
}
.active-mark.ok {
  background: var(--brand-50);
  color: var(--brand-600);
}
.active-mark.warn {
  background: var(--warn-soft);
  color: #b06a06;
}
.active-info {
  min-width: 0;
}
.active-line {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.active-provider {
  font-size: 15px;
  font-weight: 600;
  color: var(--ink-800);
}
.active-sep {
  color: var(--ink-300);
}
.active-model {
  font-size: 14px;
  font-weight: 600;
  color: var(--brand-600);
}
/* 手机：当前模型卡一行放不下（左侧信息被压到 87px，模型名与状态逐字换行），
   让「测试连通性」按钮换到下一行 */
@media (max-width: 720px) {
  .active-card {
    flex-wrap: wrap;
    gap: 10px;
  }
  .active-model {
    white-space: nowrap;
  }
}
.active-status {
  display: flex;
  align-items: center;
  gap: 7px;
  margin-top: 5px;
  font-size: 12px;
}
.warn-text {
  color: #b06a06;
}

/* 测试结果 */
.test-result {
  display: flex;
  gap: 10px;
  margin-top: 12px;
  padding: 12px 14px;
  border-radius: var(--r-md);
  font-size: 12.5px;
  line-height: 1.7;
}
.test-result.ok {
  background: var(--ok-soft);
  color: #14672f;
}
.test-result.bad {
  background: var(--danger-soft);
  color: #a32424;
}
.test-text {
  min-width: 0;
}
.test-head {
  display: flex;
  align-items: center;
  gap: 9px;
  flex-wrap: wrap;
}
.test-msg {
  margin-top: 2px;
  word-break: break-all;
  opacity: 0.85;
}

/* 供应商卡片 */
.provider-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(232px, 1fr));
  gap: 12px;
}
.provider-card {
  display: block;
  width: 100%;
  text-align: left;
  padding: 12px 14px;
  border: 1px solid var(--border);
  border-radius: var(--r-md);
  background: #fff;
  cursor: pointer;
  transition: all 0.18s ease;
  font: inherit;
}
.provider-card:hover {
  border-color: var(--brand-300);
  transform: translateY(-1px);
  box-shadow: var(--sh-xs);
}
.provider-card.on {
  border-color: var(--brand-500);
  background: var(--brand-50);
  box-shadow: 0 0 0 3px rgba(59, 110, 246, 0.1);
}
.provider-card.off {
  opacity: 0.55;
  cursor: not-allowed;
}
.provider-card.off:hover {
  transform: none;
  border-color: var(--border);
  box-shadow: none;
}
.pc-head {
  display: flex;
  align-items: center;
  gap: 8px;
}
.pc-name {
  font-size: 13.5px;
  font-weight: 600;
  color: var(--ink-700);
}
.pc-url {
  margin-top: 3px;
  font-size: 11.5px;
  color: var(--el-text-color-placeholder);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.pc-foot {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 9px;
  color: var(--el-text-color-secondary);
}

/* 切换行 */
.switch-row {
  display: flex;
  align-items: flex-end;
  gap: 14px;
  margin-top: 16px;
  flex-wrap: wrap;
}
.field {
  min-width: 260px;
}
.field-label {
  display: block;
  margin-bottom: 6px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.model-select {
  width: 280px;
}
.switch-actions {
  display: flex;
  gap: 10px;
}
.hint {
  margin-top: 12px;
  line-height: 1.7;
}

/* 生效范围 */
.scope-list {
  display: flex;
  flex-direction: column;
  gap: 9px;
  margin: 0;
  padding: 0;
  list-style: none;
}
.scope-item {
  display: flex;
  align-items: center;
  gap: 9px;
  font-size: 13px;
}
.scope-yes {
  color: var(--ok);
}
.scope-no {
  color: var(--ink-400);
}
.scope-name {
  min-width: 132px;
  color: var(--ink-700);
  font-weight: 500;
}

.code-block {
  margin-top: 15px;
  border-radius: var(--r-md);
  background: var(--ink-50);
  border: 1px solid var(--border);
  padding: 12px 14px;
}
.code-title {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-bottom: 6px;
}
.code-block pre {
  margin: 0;
  font-size: 12px;
  line-height: 1.8;
  color: var(--ink-600);
  white-space: pre-wrap;
  word-break: break-all;
}

/* 抽样与通知 */
.notify-head {
  display: flex;
  align-items: center;
  gap: 12px;
}
.notify-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}
.notify-item {
  min-width: 0;
  padding: 12px 14px;
  border: 1px solid var(--border);
  border-radius: var(--r-md);
  background: linear-gradient(180deg, #fbfcff 0%, #f7f9fe 100%);
}
.notify-label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 22px;
}
.notify-value {
  margin-top: 5px;
  font-size: 22px;
  font-weight: 700;
  line-height: 1.25;
  color: var(--ink-900);
  letter-spacing: -0.01em;
}
.notify-sub {
  margin-top: 6px;
  line-height: 1.65;
}
.notify-events {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  margin-top: 14px;
}
.notify-events .notify-label {
  flex: none;
}
.event-chips {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}
.notify-test {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 14px;
  flex-wrap: wrap;
}
.notify-fallback {
  display: flex;
  align-items: center;
  gap: 11px;
  padding: 12px 14px;
  border-radius: var(--r-md);
  background: var(--warn-soft);
  color: #8a5a08;
}
.notify-fallback-text {
  flex: 1;
  min-width: 0;
  line-height: 1.6;
}
@media (max-width: 1100px) {
  .notify-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}

/* AI 供应商管理 */
.provider-head {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
/* 嵌套在 .panel-body 中的表格：左右各吃掉 body 的 18px 内边距，
   让表格贴到卡片边缘、首列文字与 panel-head 标题同处 18px 基线。
   单元格自身内边距仍由全局 .el-table .cell（18px）提供，不叠加。 */
.table-bleed {
  margin-left: calc(-1 * var(--panel-pad-x, 18px));
  margin-right: calc(-1 * var(--panel-pad-x, 18px));
}
.table-bleed :deep(.el-table .cell) {
  padding-left: var(--table-pad-x, 18px);
  padding-right: var(--table-pad-x, 18px);
}
.provider-cell {
  min-width: 0;
}
.provider-name {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}
/* 名称行的状态胶囊更紧凑，保证 1280 下「当前 + 内置」不换行 */
.provider-name .chip {
  padding: 0 7px;
}
.price-cell {
  display: grid;
  grid-template-columns: auto auto;
  justify-content: end;
  column-gap: 6px;
  row-gap: 1px;
  font-size: 12px;
  line-height: 1.5;
  white-space: nowrap;
}
.price-row {
  display: contents;
  font-variant-numeric: tabular-nums;
}
.price-cell .mono {
  text-align: right;
}
.price-label {
  justify-self: end;
  font-size: 11.5px;
  color: var(--el-text-color-placeholder);
}
.key-chip {
  padding: 0 7px;
  font-size: 11.5px;
  letter-spacing: 0;
}
.ellipsis {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.provider-fallback {
  display: flex;
  align-items: center;
  gap: 11px;
  padding: 12px 14px;
  border-radius: var(--r-md);
  background: var(--warn-soft);
  color: #8a5a08;
}
.provider-fallback-text {
  flex: 1;
  min-width: 0;
  line-height: 1.6;
}

/* 供应商表单 / 测试对话框 */
.price-fields {
  display: flex;
  gap: 12px;
  width: 100%;
}
.price-field {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.form-hint {
  width: 100%;
  margin-top: 4px;
  line-height: 1.6;
}
.form-inline-hint {
  margin-left: 10px;
}
.delete-wrap {
  display: inline-flex;
}
.test-target {
  margin-bottom: 14px;
  padding: 12px 14px;
  border: 1px solid var(--border);
  border-radius: var(--r-md);
  background: var(--ink-50);
}
.test-target-name {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 3px;
  font-weight: 600;
  color: var(--ink-700);
}
:global(.provider-dialog .el-dialog__body) {
  max-height: 64vh;
  overflow-y: auto;
  padding: 20px;
}
@media (max-width: 760px) {
  .price-fields {
    flex-direction: column;
  }
}

/* A/B 与模型评测 */
.ab-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}
.ab-item {
  min-width: 0;
  padding: 12px 14px;
  border: 1px solid var(--border);
  border-radius: var(--r-md);
  background: linear-gradient(180deg, #fbfcff 0%, #f7f9fe 100%);
}
.ab-label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 22px;
}
.ab-value {
  margin-top: 5px;
  font-size: 20px;
  font-weight: 700;
  line-height: 1.25;
  color: var(--ink-900);
  letter-spacing: -0.01em;
}
.ab-value.ab-b {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  font-size: 14px;
}
.ab-sub {
  margin-top: 6px;
  line-height: 1.65;
}
.eval-form {
  display: flex;
  align-items: flex-end;
  gap: 12px;
  flex-wrap: wrap;
  margin-top: 16px;
}
.eval-field {
  min-width: 200px;
  flex: 1;
}
.eval-field.eval-size {
  flex: 0 0 130px;
  min-width: 130px;
}
.eval-label {
  display: block;
  margin-bottom: 6px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.eval-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  /* 主操作统一贴右：与 A/B 评测行、检索参数保存按钮右缘对齐 */
  margin-left: auto;
  padding-bottom: 1px;
}
.eval-running {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 12px;
  padding: 10px 14px;
  border-radius: var(--r-md);
  background: var(--brand-50);
  color: var(--brand-700);
}
.eval-empty {
  margin-top: 12px;
  padding: 12px 14px;
  border: 1px dashed var(--border-strong, var(--border));
  border-radius: var(--r-md);
  line-height: 1.7;
  background: var(--ink-50);
}
.eval-error {
  display: flex;
  align-items: center;
  gap: 11px;
  margin-top: 12px;
  padding: 12px 14px;
  border-radius: var(--r-md);
  background: var(--danger-soft);
  color: #a32424;
}
.eval-error-text {
  flex: 1;
  min-width: 0;
  line-height: 1.6;
}
.eval-kpis {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
  margin-top: 16px;
}
.eval-kpi {
  min-width: 0;
  padding: 12px 14px;
  border: 1px solid var(--border);
  border-radius: var(--r-md);
  background: #fbfcfe;
}
.confusion-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  flex-wrap: wrap;
  margin-top: 12px;
}
.confusion-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(74px, 1fr));
  gap: 8px;
  flex: 1;
  min-width: 320px;
}
.conf-cell {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 7px 10px;
  border-radius: var(--r-sm);
  background: var(--ink-50);
  border: 1px solid var(--border);
}
.conf-label {
  font-size: 11.5px;
  font-weight: 600;
  color: var(--el-text-color-secondary);
}
.conf-value {
  font-size: 13px;
  font-weight: 700;
  color: var(--ink-800);
}
.conf-cell.tp,
.conf-cell.tn {
  background: var(--ok-soft);
  border-color: transparent;
}
.conf-cell.fp,
.conf-cell.fn {
  background: var(--danger-soft);
  border-color: transparent;
}
.conf-legend {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}
.eval-section-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 10px;
  margin: 18px 0 10px;
}
.eval-reason {
  display: inline-block;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 12px;
  color: var(--el-text-color-regular);
}
.eval-reason.err {
  color: var(--danger);
}
@media (max-width: 1100px) {
  .ab-grid {
    grid-template-columns: minmax(0, 1fr);
  }
  .eval-kpis {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .eval-field {
    min-width: 160px;
  }
}

/* RAG 检索增强 */
.rag-switch-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 14px 16px;
  border: 1px solid var(--border);
  border-radius: var(--r-md);
  background: linear-gradient(180deg, #fbfcff 0%, #f7f9fe 100%);
}
.rag-switch-main {
  min-width: 0;
}
.rag-switch-line {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.rag-note {
  margin-top: 5px;
  line-height: 1.65;
}
.rag-section-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 10px;
  flex-wrap: wrap;
  margin: 18px 0 10px;
}
.rag-kv-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
}
.rag-kv {
  min-width: 0;
  padding: 12px 14px;
  border: 1px solid var(--border);
  border-radius: var(--r-md);
  background: #fbfcfe;
}
.rag-kv-value {
  margin-top: 5px;
  font-size: 14px;
  line-height: 1.45;
  color: var(--ink-800);
}
.rag-alert {
  margin-top: 12px;
}
.rag-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  margin-top: 14px;
}
.rag-eval-meta {
  margin-top: 10px;
  line-height: 1.7;
}
.rag-field {
  flex: 0 0 160px;
  min-width: 160px;
}
@media (max-width: 1100px) {
  .rag-kv-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
@media (max-width: 760px) {
  .rag-kv-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
