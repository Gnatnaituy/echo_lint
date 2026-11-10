<template>
  <!--
    品牌标识：圆角方块 + 声波（Echo）+ 合规对勾（Lint）。
    统一供侧栏 / 登录页 / favicon 使用，避免各处图标不一致。
  -->
  <svg
    class="brand-mark-svg"
    viewBox="0 0 64 64"
    xmlns="http://www.w3.org/2000/svg"
    role="img"
    aria-label="EchoLint"
  >
    <defs>
      <linearGradient id="echolintBrandGradient" x1="0" y1="0" x2="1" y2="1">
        <stop offset="0" stop-color="#7A9BFF" />
        <stop offset="0.5" stop-color="#3B6EF6" />
        <stop offset="1" stop-color="#2B50B4" />
      </linearGradient>
    </defs>
    <rect width="64" height="64" rx="18" fill="url(#echolintBrandGradient)" />
    <g fill="#fff">
      <rect x="14" y="26" width="6" height="12" rx="3" />
      <rect x="24" y="19" width="6" height="26" rx="3" />
      <rect x="34" y="23" width="6" height="18" rx="3" />
    </g>
    <path
      d="M44 32 L49 37.5 L56 25"
      fill="none"
      stroke="#8CF0CF"
      stroke-width="6"
      stroke-linecap="round"
      stroke-linejoin="round"
    />
  </svg>
</template>

<script setup>
/* 纯展示组件：尺寸/阴影由使用方通过 class 控制 */
</script>

<style scoped>
.brand-mark-svg {
  display: block;
  border-radius: 28.125%;
}
</style>
