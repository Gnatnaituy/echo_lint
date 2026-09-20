<template>
  <div
    class="kpi"
    :class="[`tone-${tone}`, { clickable: clickable, active }]"
    @click="clickable && $emit('click')"
  >
    <div class="kpi-top">
      <div class="icon-tile">
        <el-icon :size="17"><component :is="icon" /></el-icon>
      </div>
      <el-icon v-if="clickable" class="arrow" :size="13"><ArrowRight /></el-icon>
    </div>
    <div class="value num">{{ value }}</div>
    <div class="label">{{ label }}</div>
    <div v-if="sub" class="sub">{{ sub }}</div>
  </div>
</template>

<script setup>
defineProps({
  label: { type: String, required: true },
  value: { type: [Number, String], default: 0 },
  icon: { type: String, default: 'DataLine' },
  tone: { type: String, default: 'brand' }, // brand | ok | warn | danger | info
  sub: { type: String, default: '' },
  clickable: { type: Boolean, default: false },
  active: { type: Boolean, default: false }
})
defineEmits(['click'])
</script>

<style scoped>
.kpi {
  position: relative;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--r-lg);
  padding: 15px 16px 14px;
  box-shadow: var(--sh-xs);
  transition: transform 0.2s cubic-bezier(0.22, 1, 0.36, 1), box-shadow 0.2s ease, border-color 0.2s ease;
  overflow: hidden;
}
.kpi::after {
  content: "";
  position: absolute;
  inset: 0 auto 0 0;
  width: 3px;
  opacity: 0.9;
}
.kpi:hover {
  transform: translateY(-2px);
  box-shadow: var(--sh-md);
  border-color: var(--border-strong);
}
.kpi.clickable {
  cursor: pointer;
}
.kpi.active {
  border-color: var(--brand-300);
  box-shadow: 0 0 0 3px var(--brand-50);
}

.kpi-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
}
.icon-tile {
  width: 34px;
  height: 34px;
  border-radius: 10px;
  display: grid;
  place-items: center;
}
.arrow {
  color: var(--ink-300);
  transition: transform 0.2s ease, color 0.2s ease;
}
.kpi:hover .arrow {
  color: var(--brand-500);
  transform: translateX(2px);
}

.value {
  font-size: 26px;
  font-weight: 700;
  line-height: 1.1;
  color: var(--ink-900);
  letter-spacing: -0.02em;
}
.label {
  margin-top: 3px;
  font-size: 12.5px;
  color: var(--el-text-color-secondary);
}
.sub {
  margin-top: 6px;
  font-size: 11.5px;
  color: var(--el-text-color-placeholder);
}

.tone-brand::after { background: var(--brand-500); }
.tone-brand .icon-tile { background: var(--brand-50); color: var(--brand-600); }
.tone-ok::after { background: var(--ok); }
.tone-ok .icon-tile { background: var(--ok-soft); color: var(--ok); }
.tone-ok .value { color: #12703a; }
.tone-warn::after { background: var(--warn); }
.tone-warn .icon-tile { background: var(--warn-soft); color: #b06a06; }
.tone-warn .value { color: #a8630a; }
.tone-danger::after { background: var(--danger); }
.tone-danger .icon-tile { background: var(--danger-soft); color: #c92c2c; }
.tone-danger .value { color: #c92c2c; }
.tone-info::after { background: var(--info); }
.tone-info .icon-tile { background: var(--info-soft); color: var(--info); }
</style>
