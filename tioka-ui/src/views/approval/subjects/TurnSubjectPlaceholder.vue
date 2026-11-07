<template>
  <div class="subject-card">工具审批 · turn</div>
</template>
<script setup lang="ts">
defineProps<{ subjectType?: string }>()
</script>
<style scoped>
.subject-card {
  border: 1px dashed #334155;
  border-radius: 8px;
  padding: 10px 12px;
  color: var(--muted, #8aa0b5);
  font-size: 13px;
}
</style>
