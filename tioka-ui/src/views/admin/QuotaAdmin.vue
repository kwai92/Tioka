<template>
  <div class="page">
    <h1>配额与成本</h1>
    <el-alert v-if="msg" :title="msg" type="success" show-icon class="mb" @close="msg = ''" />
    <el-alert v-if="error" :title="error" type="error" show-icon class="mb" @close="error = ''" />

    <el-card shadow="never" class="mb">
      <template #header>空间配额</template>
      <el-form :inline="true">
        <el-form-item label="空间 ID">
          <el-input v-model="scopeId" placeholder="当前空间或指定 ID" style="width: 220px" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="busy" @click="loadQuotas">刷新</el-button>
        </el-form-item>
      </el-form>
      <el-table :data="quotas" size="small" stripe>
        <el-table-column prop="metric" label="指标" width="160" />
        <el-table-column label="上限" width="160">
          <template #default="{ row }">
            <el-input-number
              v-model="editLimits[row.metric]"
              :disabled="!canAdmin"
              :min="0"
              controls-position="right"
            />
          </template>
        </el-table-column>
        <el-table-column prop="used" label="已用" width="100" />
        <el-table-column label="本月进度" min-width="180">
          <template #default="{ row }">
            <el-progress :percentage="usagePercent(row)" :status="usagePercent(row) >= 90 ? 'exception' : undefined" />
          </template>
        </el-table-column>
        <el-table-column label="" width="100">
          <template #default="{ row }">
            <el-button v-if="canAdmin" link type="primary" size="small" @click="saveLimit(row.metric)">保存</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card shadow="never">
      <template #header>成本（按空间汇总 / 趋势）</template>
      <el-button size="small" :loading="busy" @click="loadCost">刷新成本</el-button>
      <el-table :data="summary" size="small" stripe class="mt">
        <el-table-column prop="key" label="Key" min-width="120" />
        <el-table-column prop="name" label="名称" width="140" />
        <el-table-column prop="calls" label="调用" width="80" />
        <el-table-column prop="promptTokens" label="prompt" width="100" />
        <el-table-column prop="completionTokens" label="completion" width="110" />
        <el-table-column label="成本 (USD)" width="120">
          <template #default="{ row }">{{ (row.costMicros / 1_000_000).toFixed(4) }}</template>
        </el-table-column>
      </el-table>
      <h3 class="mt">趋势（日）</h3>
      <el-table :data="trend" size="small" stripe>
        <el-table-column prop="day" label="日期" min-width="160" />
        <el-table-column label="成本 (USD)" width="120">
          <template #default="{ row }">{{ (row.costMicros / 1_000_000).toFixed(4) }}</template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import {
  costSummary,
  costTrend,
  listQuotas,
  putQuota,
  type CostBucket,
  type CostPoint,
  type QuotaView
} from '@/admin/adminApi'
import { hasAnyPermission } from '@/admin/adminNav'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const canAdmin = computed(() => hasAnyPermission(auth.permissions, ['quota:admin', 'tenant:admin', '*']))
const scopeId = ref(auth.currentWorkspaceId || '')
const quotas = ref<QuotaView[]>([])
const editLimits = reactive<Record<string, number | undefined>>({})
const summary = ref<CostBucket[]>([])
const trend = ref<CostPoint[]>([])
const busy = ref(false)
const msg = ref('')
const error = ref('')

function usagePercent(row: QuotaView): number {
  if (row.limit == null || row.limit <= 0) return 0
  return Math.min(100, Math.round((100 * row.used) / row.limit))
}

async function loadQuotas() {
  if (!scopeId.value) {
    error.value = '请填写空间 ID'
    return
  }
  busy.value = true
  error.value = ''
  try {
    quotas.value = await listQuotas('workspace', scopeId.value)
    for (const q of quotas.value) {
      editLimits[q.metric] = q.limit ?? undefined
    }
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    busy.value = false
  }
}

async function saveLimit(metric: string) {
  const limit = editLimits[metric]
  await putQuota('workspace', scopeId.value, metric, limit == null ? null : Number(limit))
  msg.value = `${metric} 已保存`
  await loadQuotas()
}

async function loadCost() {
  busy.value = true
  error.value = ''
  try {
    summary.value = await costSummary('workspace')
    if (scopeId.value) {
      trend.value = await costTrend('workspace', scopeId.value)
    } else {
      trend.value = []
    }
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    busy.value = false
  }
}

onMounted(async () => {
  if (!scopeId.value && auth.currentWorkspaceId) scopeId.value = auth.currentWorkspaceId
  await loadQuotas()
  await loadCost()
})
</script>

<style scoped>
.mb { margin-bottom: 12px; }
.mt { margin-top: 12px; }
</style>
