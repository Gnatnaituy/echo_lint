<template>
  <div
    class="kpi"
    :class="[`tone-${tone}`, { clickable: clickable, active }]"
    @click="clickable && $emit('click')"
  >
    <div class="kpi-top">
      <div class="icon-tile">
        <el-icon :size="17"><component :is="iconComponent" /></el-icon>
      </div>
      <el-icon v-if="clickable" class="arrow" :size="13"><ArrowRight /></el-icon>
    </div>
    <div class="value num">{{ value }}</div>
    <div class="label">{{ label }}</div>
    <div v-if="sub" class="sub">{{ sub }}</div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { ArrowRight, resolveIcon } from './icons.js'

const props = defineProps({
  label: { type: String, required: true },
  value: { type: [Number, String], default: 0 },
  /** 图标：可传 ICON_MAP 名称字符串（各视图现有写法），也可直接传组件对象 */
  icon: { type: [String, Object], default: 'DataLine' },
  tone: { type: String, default: 'brand' }, // brand | ok | warn | danger | info
  sub: { type: String, default: '' },
  clickable: { type: Boolean, default: false },
  active: { type: Boolean, default: false }
})
defineEmits(['click'])

/** 字符串 icon 经显式图标表解析（审计 P3-1：不再依赖全局注册） */
const iconComponent = computed(() => resolveIcon(props.icon))
</script>

<style scoped>
.kpi {
  position: relative;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--r-lg);
  padding: 15px var(--panel-pad-x, 18px) 14px;
  box-shadow: var(--sh-sm);
  transition:
    transform 0.2s cubic-bezier(0.22, 1, 0.36, 1),
    box-shadow 0.2s ease,
    border-color 0.2s ease;
  overflow: hidden;
}
/* 顶部 3px 语义色条：比左侧竖条更符合「指标卡」的阅读顺序，
   也让 6 张卡在网格里形成一条可扫读的彩色序列 */
.kpi::after {
  content: '';
  position: absolute;
  inset: 0 0 auto 0;
  height: 3px;
  opacity: 0.85;
}
/* 卡片本体保持纯白：语义只由顶部色条与数值颜色表达。
   整卡铺语义色底（哪怕是极淡的）在 6 张并排时会五颜六色，故不采用。 */
.kpi.clickable:hover {
  transform: translateY(-2px);
  box-shadow: var(--sh-md);
  border-color: var(--border-strong);
}
.kpi.clickable {
  cursor: pointer;
}
.kpi.active {
  border-color: var(--brand-300);
  box-shadow:
    0 0 0 3px var(--brand-50),
    var(--sh-sm);
}

.kpi-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}
.icon-tile {
  width: 32px;
  height: 32px;
  border-radius: 9px;
  display: grid;
  place-items: center;
  position: relative;
  z-index: 1;
}
.arrow {
  color: var(--ink-300);
  transition:
    transform 0.2s ease,
    color 0.2s ease;
}
.kpi:hover .arrow {
  color: var(--brand-500);
  transform: translateX(2px);
}

.value {
  font-size: 28px;
  font-weight: 700;
  line-height: 1.05;
  color: var(--ink-900);
  letter-spacing: -0.02em;
}
.label {
  margin-top: 5px;
  font-size: 12.5px;
  font-weight: 500;
  color: var(--ink-600);
}
.sub {
  margin-top: 6px;
  font-size: 11.5px;
  /* 11.5px 小字用默认 placeholder 灰对比不足，取深一档 */
  color: var(--ink-500);
}

.tone-brand::after {
  background: linear-gradient(90deg, var(--brand-500), var(--brand-300));
}
.tone-brand .icon-tile {
  background: var(--brand-50);
  color: var(--brand-600);
}
.tone-ok::after {
  background: linear-gradient(90deg, var(--ok), #7bd3a0);
}
.tone-ok .icon-tile {
  background: var(--ok-soft);
  color: var(--ok);
}
.tone-ok .value {
  color: #12703a;
}
.tone-warn::after {
  background: linear-gradient(90deg, var(--warn), #fbd38d);
}
.tone-warn .icon-tile {
  background: var(--warn-soft);
  color: var(--warn-ink);
}
.tone-warn .value {
  color: var(--warn-ink);
}
.tone-danger::after {
  background: linear-gradient(90deg, var(--danger), #f9a8a8);
}
.tone-danger .icon-tile {
  background: var(--danger-soft);
  color: #c92c2c;
}
.tone-danger .value {
  color: #c92c2c;
}
.tone-info::after {
  background: linear-gradient(90deg, var(--info), #b6c1d1);
}
.tone-info .icon-tile {
  background: var(--info-soft);
  color: var(--info);
}
</style>
