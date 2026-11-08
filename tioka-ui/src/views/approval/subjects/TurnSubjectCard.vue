<template>
  <div class="subject-card turn">
    <div class="head">
      <el-tag size="small" type="primary" effect="dark">对话 · turn</el-tag>
      <el-tag v-if="approval?.risk" size="small" :type="riskType" effect="plain">
        {{ approval.risk }}
      </el-tag>
    </div>
    <p class="title">{{ approval?.title || '工具审批' }}</p>
    <p v-if="approval?.toolName" class="meta">工具 {{ approval.toolName }}</p>
    <p v-if="approval?.summary" class="summary">{{ approval.summary }}</p>
    <a v-if="approval?.link?.url" class="link" :href="approval.link.url">
      {{ approval.link.title || '查看轨迹' }}
    </a>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { ApprovalView } from '@/api/approval'

const props = defineProps<{
  subjectType?: string
  approval?: ApprovalView | null
}>()

const riskType = computed(() => {
  const r = props.approval?.risk
  if (r === 'CRITICAL' || r === 'HIGH') return 'danger'
  if (r === 'MEDIUM') return 'warning'
  return 'info'
})
</script>

<style scoped>
.subject-card {
  border: 1px solid #334155;
  border-radius: 8px;
  padding: 12px;
  display: grid;
  gap: 6px;
}
.head { display: flex; gap: 8px; flex-wrap: wrap; align-items: center; }
.title { margin: 0; font-weight: 600; }
.meta, .summary { margin: 0; font-size: 13px; color: var(--muted, #8aa0b5); }
.link { font-size: 13px; color: #60a5fa; }
</style>
