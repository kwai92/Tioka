<template>
  <div class="state-stale" role="status">
    <span>此对象已被他人更新</span>
    <button type="button" @click="emit('diff')">查看差异</button>
    <button type="button" @click="emit('reload')">重新加载</button>
  </div>
</template>

<script setup lang="ts">
const emit = defineEmits<{ diff: []; reload: [] }>()
</script>

<style scoped>
.state-stale {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  padding: 8px 12px;
  border-radius: 8px;
  background: color-mix(in srgb, #ca8a04 25%, transparent);
  color: var(--text);
}
button {
  cursor: pointer;
  border: 1px solid #334155;
  background: transparent;
  color: var(--text);
  border-radius: 6px;
  padding: 4px 10px;
}
</style>
