<template>
  <div class="state-long" role="status" aria-live="polite">
    <p>
      后台进行中<span v-if="waitedMinutes != null">，已等待 {{ waitedMinutes }} 分钟</span>
    </p>
    <p class="hint">离开本页不会中断任务；完成后将收到站内通知。</p>
    <div v-if="progress != null" class="bar" aria-hidden="true">
      <div class="fill" :style="{ width: `${Math.min(100, Math.max(0, progress))}%` }" />
    </div>
  </div>
</template>

<script setup lang="ts">
defineProps<{
  waitedMinutes?: number
  /** 0–100；未知则不显示条 */
  progress?: number
}>()
</script>

<style scoped>
.state-long {
  display: grid;
  gap: 6px;
  color: var(--muted);
}
.hint {
  margin: 0;
  font-size: 0.85rem;
}
.bar {
  height: 6px;
  border-radius: 4px;
  background: #1e293b;
  overflow: hidden;
}
.fill {
  height: 100%;
  background: #3b82f6;
}
</style>
