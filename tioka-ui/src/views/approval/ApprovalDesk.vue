<template>
  <div class="page">
    <header class="head">
      <div>
        <h1>审批台</h1>
        <p class="muted">三类主体按注册表渲染；批量处理；SSE / 飞书状态同步</p>
      </div>
      <el-badge :value="badgeCount" :hidden="badgeCount === 0" type="warning">
        <el-tag effect="plain">待我审批</el-tag>
      </el-badge>
    </header>

    <el-alert v-if="error" :title="error" type="error" show-icon class="mb" @close="error = ''" />
    <el-alert
      v-if="batchSummary"
      type="info"
      show-icon
      class="mb"
      :closable="true"
      :title="`批量结果：成功 ${batchSummary.successCount} · 失败 ${batchSummary.failureCount}`"
      @close="batchSummary = null"
    >
      <ul class="batch-lines">
        <li v-for="line in batchSummary.lines" :key="line.id" :class="{ fail: !line.ok }">
          {{ line.text }}
        </li>
      </ul>
    </el-alert>

    <el-tabs v-model="scope" @tab-change="reload">
      <el-tab-pane label="待我审批" name="ASSIGNED_TO_ME" />
      <el-tab-pane label="我发起的" name="REQUESTED_BY_ME" />
      <el-tab-pane v-if="canSeeAll" label="全部" name="WORKSPACE_ALL" />
    </el-tabs>

    <el-form :inline="true" class="filters mb" @submit.prevent>
      <el-form-item label="来源">
        <el-select v-model="subjectType" clearable placeholder="全部" style="width: 140px" @change="reload">
          <el-option label="对话" value="turn" />
          <el-option label="编排" value="orch_run" />
          <el-option label="Flow" value="flow_node_run" />
        </el-select>
      </el-form-item>
      <el-form-item label="风险">
        <el-select v-model="risk" clearable placeholder="全部" style="width: 120px" @change="reload">
          <el-option v-for="r in risks" :key="r" :label="r" :value="r" />
        </el-select>
      </el-form-item>
      <el-form-item label="状态">
        <el-select v-model="status" clearable placeholder="全部" style="width: 120px" @change="reload">
          <el-option label="待审批" value="PENDING" />
          <el-option label="已批准" value="APPROVED" />
          <el-option label="已拒绝" value="REJECTED" />
          <el-option label="已过期" value="EXPIRED" />
        </el-select>
      </el-form-item>
      <el-form-item label="关键词">
        <el-input v-model="keyword" clearable placeholder="标题 / 工具" @keyup.enter="reload" />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" :loading="loading" @click="reload">查询</el-button>
        <el-button :disabled="!selectedIds.length" :loading="batchBusy" @click="runBatch(true)">
          批量批准
        </el-button>
        <el-button
          type="danger"
          plain
          :disabled="!selectedIds.length"
          :loading="batchBusy"
          @click="runBatch(false)"
        >
          批量拒绝
        </el-button>
      </el-form-item>
    </el-form>

    <el-empty v-if="!loading && !items.length" description="暂无审批" />

    <el-table
      v-else
      v-loading="loading"
      :data="items"
      row-key="id"
      @selection-change="onSelectionChange"
      @row-click="openDetail"
    >
      <el-table-column type="selection" width="48" :selectable="rowSelectable" />
      <el-table-column label="标题" min-width="220">
        <template #default="{ row }">
          <div class="title-cell">
            <strong>{{ row.title }}</strong>
            <span class="muted">{{ subjectLabel(row.subjectType) }}</span>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="风险" width="100">
        <template #default="{ row }">
          <el-tag v-if="row.risk" size="small" :type="riskTag(row.risk)">{{ row.risk }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="发起人" width="120">
        <template #default="{ row }">{{ row.requestedByName || row.requestedBy || '—' }}</template>
      </el-table-column>
      <el-table-column label="剩余时间" width="120">
        <template #default="{ row }">{{ remainingLabel(row.expiresAt) }}</template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">{{ row.status }}</template>
      </el-table-column>
    </el-table>

    <ApprovalDetailDrawer v-model="drawerOpen" :approval="active" @decided="onDecided" />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import {
  applyApprovalBadgeEvent,
  batchDecide,
  buildApprovalListQuery,
  countApprovals,
  formatBatchResults,
  listApprovals,
  streamApprovals,
  type ApprovalScope,
  type ApprovalStatus,
  type ApprovalSubjectType,
  type ApprovalView,
  type BatchResultSummary,
  type ToolRisk
} from '@/api/approval'
import { useAuthStore } from '@/stores/auth'
import ApprovalDetailDrawer from './ApprovalDetailDrawer.vue'
import { SUBJECT_LABELS } from './subjectRegistry'

const auth = useAuthStore()
const canSeeAll = computed(() => (auth.permissions || []).includes('workspace:manage'))

const scope = ref<ApprovalScope>('ASSIGNED_TO_ME')
const status = ref<ApprovalStatus | ''>('PENDING')
const subjectType = ref<ApprovalSubjectType | ''>('')
const risk = ref<ToolRisk | ''>('')
const keyword = ref('')
const risks: ToolRisk[] = ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL']

const items = ref<ApprovalView[]>([])
const selectedIds = ref<string[]>([])
const loading = ref(false)
const batchBusy = ref(false)
const error = ref('')
const batchSummary = ref<BatchResultSummary | null>(null)
const badgeCount = ref(0)

const drawerOpen = ref(false)
const active = ref<ApprovalView | null>(null)

let stopStream: (() => void) | undefined

function subjectLabel(t: ApprovalSubjectType | string): string {
  return SUBJECT_LABELS[t as ApprovalSubjectType] || t
}

function riskTag(r: ToolRisk) {
  if (r === 'CRITICAL' || r === 'HIGH') return 'danger'
  if (r === 'MEDIUM') return 'warning'
  return 'info'
}

function remainingLabel(expiresAt?: string | null): string {
  if (!expiresAt) return '—'
  const ms = Date.parse(expiresAt) - Date.now()
  if (Number.isNaN(ms)) return '—'
  if (ms <= 0) return '已过期'
  const h = Math.floor(ms / 3_600_000)
  const m = Math.floor((ms % 3_600_000) / 60_000)
  if (h > 48) return `${Math.floor(h / 24)} 天`
  if (h > 0) return `${h} 小时`
  return `${m} 分钟`
}

function rowSelectable(row: ApprovalView) {
  return row.status === 'PENDING'
}

function onSelectionChange(rows: ApprovalView[]) {
  selectedIds.value = rows.map((r) => r.id)
}

function openDetail(row: ApprovalView) {
  active.value = row
  drawerOpen.value = true
}

async function reload() {
  loading.value = true
  error.value = ''
  try {
    const q = buildApprovalListQuery({
      scope: scope.value,
      status: status.value || undefined,
      subjectType: subjectType.value || undefined,
      risk: risk.value || undefined,
      keyword: keyword.value.trim() || undefined,
      page: 0,
      size: 50
    })
    const page = await listApprovals(q)
    items.value = page.items
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
}

async function refreshBadge() {
  try {
    badgeCount.value = await countApprovals('ASSIGNED_TO_ME', 'PENDING')
  } catch {
    /* 角标失败不阻断列表 */
  }
}

async function runBatch(approved: boolean) {
  if (!selectedIds.value.length) return
  batchBusy.value = true
  error.value = ''
  try {
    const results = await batchDecide({
      ids: [...selectedIds.value],
      approved
    })
    batchSummary.value = formatBatchResults(results)
    selectedIds.value = []
    await reload()
    await refreshBadge()
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    batchBusy.value = false
  }
}

async function onDecided() {
  await reload()
  await refreshBadge()
}

function notifyNewApproval(title: string) {
  if (typeof Notification === 'undefined') return
  if (Notification.permission === 'granted') {
    new Notification('新的审批', { body: title })
  } else if (Notification.permission === 'default') {
    void Notification.requestPermission()
  }
}

onMounted(async () => {
  await reload()
  await refreshBadge()
  stopStream = streamApprovals({
    onEvent(ev) {
      badgeCount.value = applyApprovalBadgeEvent(badgeCount.value, ev)
      if (ev.type === 'approval.requested') {
        notifyNewApproval(ev.approval?.title || '待处理审批')
      }
      // 飞书卡片决定后 SSE 推 decided，刷新列表以多端同步
      if (ev.type === 'approval.decided' || ev.type === 'approval.requested') {
        void reload()
      }
    }
  })
})

onUnmounted(() => {
  stopStream?.()
})

defineExpose({ selectedIds, runBatch, items, badgeCount, reload })
</script>

<style scoped>
.page { display: grid; gap: 12px; max-width: 1100px; }
.head { display: flex; justify-content: space-between; align-items: flex-start; gap: 12px; }
h1 { margin: 0; }
.muted { color: var(--muted, #8aa0b5); margin: 4px 0 0; font-size: 13px; }
.mb { margin-bottom: 4px; }
.filters { flex-wrap: wrap; }
.title-cell { display: grid; gap: 2px; }
.batch-lines { margin: 8px 0 0; padding-left: 18px; }
.batch-lines .fail { color: #f87171; }
</style>
