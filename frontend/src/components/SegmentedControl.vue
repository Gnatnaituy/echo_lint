<template>
  <div class="seg" role="group" :aria-label="ariaLabel || undefined" :title="ariaLabel || undefined">
    <button
      v-for="opt in options"
      :key="String(opt.value)"
      type="button"
      class="seg-item"
      :class="{ on: isActive(opt) }"
      :aria-pressed="isActive(opt) ? 'true' : 'false'"
      @click="select(opt)"
    >
      <span class="seg-label">{{ opt.label }}</span>
      <span
        v-if="opt.count !== undefined && opt.count !== null"
        class="seg-count"
        :class="{ hot: opt.hot === true }"
      >{{ opt.count }}</span>
    </button>
  </div>
</template>

<script setup>
/**
 * 分段筛选控件（枚举型过滤选项统一用这个组件）。
 *
 * 外观与人工复检页「待复检 / 已复检」一致：灰色轨道 + 选中白色胶囊 + 品牌蓝文字，
 * 可选数字角标（count>0 且 hot=true 时用红色，如「待复检 5」）。
 *
 * 用法：
 *   <SegmentedControl v-model="statusFilter" :options="statusOptions" @change="reload" />
 *   options: [{ value: '', label: '全部', count: 9 }, ...]（value 与 v-model 严格相等匹配）
 */
const props = defineProps({
  /** 当前选中值；「全部」建议用空字符串保持与现有查询参数一致 */
  modelValue: { type: [String, Number, Boolean], default: '' },
  /** [{ value, label, count?, hot? }] */
  options: { type: Array, required: true },
  ariaLabel: { type: String, default: '' }
})
const emit = defineEmits(['update:modelValue', 'change'])

const isActive = (opt) => props.modelValue === opt.value

function select(opt) {
  if (isActive(opt)) return
  emit('update:modelValue', opt.value)
  emit('change', opt.value)
}
</script>

<style scoped>
.seg {
  display: inline-flex;
  align-items: center;
  gap: 1px;
  padding: 2px;
  border-radius: 10px;
  background: var(--ink-100);
  max-width: 100%;
  flex-wrap: wrap;
}
.seg-item {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  height: 26px;
  padding: 0 10px;
  border: none;
  border-radius: 8px;
  background: transparent;
  color: var(--el-text-color-regular);
  font-family: inherit;
  font-size: 12px;
  font-weight: 500;
  white-space: nowrap;
  cursor: pointer;
  transition: all 0.16s ease;
}
.seg-item:hover:not(.on) {
  color: var(--brand-600);
}
.seg-item.on {
  background: #fff;
  color: var(--brand-600);
  box-shadow: var(--sh-xs);
}
.seg-count {
  min-width: 16px;
  height: 17px;
  padding: 0 4px;
  border-radius: 999px;
  background: var(--ink-200);
  color: var(--ink-500);
  font-size: 10.5px;
  font-weight: 600;
  display: grid;
  place-items: center;
  font-variant-numeric: tabular-nums;
}
.seg-count.hot {
  background: var(--danger);
  color: #fff;
}
</style>
