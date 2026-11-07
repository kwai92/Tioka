<template>
  <div class="page">
    <h1>配额与成本</h1>
    <p class="muted">
      演示链路：选空间 → 查看/编辑配额与本月进度 → 刷新成本汇总与日趋势（GOV-03 / GOV-05）。
    </p>
    <el-alert v-if="msg" :title="msg" type="success" show-icon class="mb" @close="msg = ''" />
    <el-alert v-if="error" :title="error" type="error" show-icon class="mb" @close="error = ''" />

    <el-card shadow="never" class="mb">
      <template #header>空间配额</template>
      <el-form :inline="true">
        <el-form-item label="空间">
          <el-select
            v-model="scopeId"
            filterable
            allow-create
            default-first-option
            placeholder="选择或输入空间 ID"
            style="width: 280px"
            @change="onScopeChange"
          >
            <el-option
              v-for="w in workspaces"
              :key="w.id"
              :label="`${w.name} (${w.id})`"
              :value="w.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="busy" @click="loadQuotas">刷新配额</el-button>
        </el-form-item>
      </el-form>
      <el-empty v-if="!quotas.length && !busy" description="暂无配额记录；可编辑后保存上限" :image-size="56" />
      <el-table v-else :data="quotas" size="small" stripe>
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
            <el-progress
              :percentage="usagePercent(row)"
              :status="usagePercent(row) >= 90 ? 'exception' : undefined"
            />
          </template>
        </el-table-column>
        <el-table-column label="" width="100">
          <template #default="{ row }">
            <el-button v-if="canAdmin" link type="primary" size="small" @click="saveLimit(row.metric)">
              保存
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card shadow="never">
      <template #header>成本（按空间汇总 / 趋势）</template>
      <el-form :inline="true" class="mb">
        <el-form-item label="区间">
          <el-date-picker
            v-model="range"
            type="daterange"
            value-format="YYYY-MM-DD"
            start-placeholder="起"
            end-placeholder="止"
            style="width: 260px"
          />
        </el-form-item>
        <el-form-item>
          <el-button size="small" :loading="busy" @click="loadCost">刷新成本</el-button>
        </el-form-item>
      </el-form>
      <el-table :data="summary" size="small" stripe>
        <el-table-column prop="key" label="Key" min-width="120" />
        <el-table-column prop="name" label="名称" width="140" />
        <el-table-column prop="calls" label="调用" width="80" />
        <el-table-column prop="promptTokens" label="prompt" width="100" />
        <el-table-column prop="completionTokens" label="completion" width="110" />
        <el-table-column label="成本 (USD)" width="120">
          <template #default="{ row }">{{ (row.costMicros / 1_000_000).toFixed(4) }}</template>
        </el-table-column>
      </el-table>
      <h3 class="mt">趋势（日）· {{ scopeId || '未选空间' }}</h3>
      <el-table :data="trend" size="small" stripe>
        <el-table-column prop="day" label="日期" min-width="160" />
        <el-table-column label="成本 (USD)" width="120">
          <template #default="{ row }">{{ (row.costMicros / 1_000_000).toFixed(4) }}</template>
        </el-table-column>
        <el-table-column prop="calls" label="调用" width="80" />
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
  listWorkspaces,
  putQuota,
  type CostBucket,
  type CostPoint,
  type QuotaView,
  type WorkspaceView
} from '@/admin/adminApi'
import { hasAnyPermission } from '@/admin/adminNav'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const canAdmin = computed(() => hasAnyPermission(auth.permissions, ['quota:admin', 'tenant:admin', '*']))
const workspaces = ref<WorkspaceView[]>([])
const scopeId = ref(auth.currentWorkspaceId || '')
const quotas = ref<QuotaView[]>([])
const editLimits = reactive<Record<string, number | undefined>>({})
const summary = ref<CostBucket[]>([])
const trend = ref<CostPoint[]>([])
const busy = ref(false)
const msg = ref('')
const error = ref('')
const range = ref<[string, string] | null>(defaultMonthRange())

function defaultMonthRange(): [string, string] {
  const now = new Date()
  const y = now.getFullYear()
  const m = String(now.getMonth() + 1).padStart(2, '0')
  const d = String(now.getDate()).padStart(2, '0')
  return [`${y}-${m}-01`, `${y}-${m}-${d}`]
}

function usagePercent(row: QuotaView): number {
  if (row.limit == null || row.limit <= 0) return 0
  return Math.min(100, Math.round((100 * row.used) / row.limit))
}

async function loadWorkspaceOptions() {
  try {
    workspaces.value = await listWorkspaces()
    if (!scopeId.value && workspaces.value.length) {
      scopeId.value = workspaces.value[0].id
    }
  } catch {
    workspaces.value = []
  }
}

async function loadQuotas() {
  if (!scopeId.value) {
    error.value = '请选择空间'
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
    const from = range.value?.[0]
    const to = range.value?.[1]
    summary.value = await costSummary('workspace', from, to)
    if (scopeId.value) {
      trend.value = await costTrend('workspace', scopeId.value, from, to)
    } else {
      trend.value = []
    }
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    busy.value = false
  }
}

async function onScopeChange() {
  await loadQuotas()
  await loadCost()
}

onMounted(async () => {
  if (!scopeId.value && auth.currentWorkspaceId) scopeId.value = auth.currentWorkspaceId
  await loadWorkspaceOptions()
  await loadQuotas()
  await loadCost()
})
</script>

<style scoped>
.mb { margin-bottom: 12px; }
.mt { margin-top: 12px; }
.muted { color: var(--muted); font-size: 0.9rem; }
</style>
