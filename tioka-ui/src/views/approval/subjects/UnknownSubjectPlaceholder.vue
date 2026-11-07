<template>
  <div class="subject-card">未知审批主体 · {{ subjectType || 'unknown' }}</div>
</template>
<script setup lang="ts">
defineProps<{ subjectType?: string }>()
</script>
<style scoped>
.subject-card {
  border: 1px dashed #7c3aed;
  border-radius: 8px;
  padding: 10px 12px;
  color: var(--muted, #8aa0b5);
  font-size: 13px;
}
</style>
