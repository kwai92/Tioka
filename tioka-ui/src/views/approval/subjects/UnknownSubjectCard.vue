<template>
  <div class="subject-card unknown">
    <el-tag size="small" type="info" effect="plain">未知主体 · {{ subjectType || 'unknown' }}</el-tag>
    <p class="title">{{ approval?.title || '无法识别的审批主体' }}</p>
    <p class="summary">请升级前端或登记新渲染器（F27）。</p>
  </div>
</template>

<script setup lang="ts">
import type { ApprovalView } from '@/api/approval'

defineProps<{
  subjectType?: string
  approval?: ApprovalView | null
}>()
</script>

<style scoped>
.subject-card {
  border: 1px dashed #7c3aed;
  border-radius: 8px;
  padding: 12px;
  display: grid;
  gap: 6px;
}
.title { margin: 0; font-weight: 600; }
.summary { margin: 0; font-size: 13px; color: var(--muted, #8aa0b5); }
</style>
