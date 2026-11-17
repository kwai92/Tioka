<template>
  <div class="page">
    <h1>审计</h1>
    <el-alert v-if="error" :title="error" type="error" show-icon class="mb" @close="error = ''" />

    <el-card shadow="never" class="mb">
      <el-form :inline="true">
        <el-form-item label="操作人">
          <el-input v-model="filters.actorId" clearable />
        </el-form-item>
        <el-form-item label="动作">
          <el-input v-model="filters.action" clearable />
        </el-form-item>
        <el-form-item label="结果">
          <el-select v-model="filters.outcome" clearable style="width: 120px">
            <el-option label="SUCCESS" value="SUCCESS" />
            <el-option label="DENIED" value="DENIED" />
            <el-option label="FAILURE" value="FAILURE" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="busy" @click="reload">查询</el-button>
          <el-button @click="onExport" v-if="canExport">导出 CSV</el-button>
        </el-form-item>
      </el-form>
      <el-form :inline="true">
        <el-form-item label="Trace ID">
          <el-input v-model="filters.traceId" clearable style="width: 180px" />
        </el-form-item>
        <el-form-item label="请求 ID">
          <el-input v-model="filters.requestId" clearable style="width: 180px" />
        </el-form-item>
        <el-form-item label="主体">
          <el-input v-model="filters.subject" clearable style="width: 140px" />
        </el-form-item>
        <el-form-item label="Turn">
          <el-input v-model="filters.turnId" clearable style="width: 140px" />
        </el-form-item>
        <el-form-item label="Run">
          <el-input v-model="filters.runId" clearable style="width: 140px" />
        </el-form-item>
      </el-form>
      <p class="muted">默认近 7 天（后端缺省）；可按操作人 / 动作 / 结果与关联键（Trace / 请求 / 主体 / Turn / Run）筛选。</p>
      <el-table :data="rows" size="small" stripe v-loading="busy">
        <el-table-column prop="ts" label="时间" min-width="170" />
        <el-table-column prop="actorId" label="操作人" width="100" />
        <el-table-column prop="action" label="动作" min-width="140" />
        <el-table-column prop="resourceType" label="资源类型" width="110" />
        <el-table-column prop="resourceId" label="资源 ID" min-width="120" />
        <el-table-column prop="outcome" label="结果" width="90" />
        <el-table-column prop="reason" label="原因" min-width="120" />
        <el-table-column prop="traceId" label="Trace" min-width="120" />
        <el-table-column prop="turnId" label="Turn" min-width="110" />
      </el-table>
      <div v-if="nextCursor" class="more">
        <el-button :loading="busy" @click="loadMore">加载更多</el-button>
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { auditExportUrl, queryAudit, type AuditEvent } from '@/admin/adminApi'
import { hasAnyPermission } from '@/admin/adminNav'
import { authHeaders } from '@/api/http'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
/** OBS-09 / ADR-072 §6：导出为特权操作（写审计 + 证据包），按 `audit:export` 控制可见性。 */
const canExport = computed(() =>
  hasAnyPermission(auth.permissions, ['audit:export', 'tenant:admin', 'system:admin', '*'])
)
const rows = ref<AuditEvent[]>([])
const busy = ref(false)
const error = ref('')
const nextCursor = ref<string | undefined>(undefined)
const filters = reactive({
  actorId: '',
  action: '',
  outcome: '',
  traceId: '',
  requestId: '',
  subject: '',
  turnId: '',
  runId: ''
})

/** 仅筛选条件（不含分页参数）——导出与首屏查询共用。 */
function filterParams(): Record<string, string | undefined> {
  return {
    actorId: filters.actorId || undefined,
    action: filters.action || undefined,
    outcome: filters.outcome || undefined,
    traceId: filters.traceId || undefined,
    requestId: filters.requestId || undefined,
    subject: filters.subject || undefined,
    turnId: filters.turnId || undefined,
    runId: filters.runId || undefined
  }
}

/** 首屏 / 重新查询：丢弃旧游标，替换结果集。 */
async function reload() {
  busy.value = true
  error.value = ''
  try {
    const page = await queryAudit({ ...filterParams(), size: '50' })
    rows.value = page.rows
    nextCursor.value = page.nextCursor
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    busy.value = false
  }
}

/** 按服务端 `(ts, id)` 游标续查下一页，追加到结果集。 */
async function loadMore() {
  if (!nextCursor.value) return
  busy.value = true
  error.value = ''
  try {
    const page = await queryAudit({ ...filterParams(), size: '50', cursor: nextCursor.value })
    rows.value = [...rows.value, ...page.rows]
    nextCursor.value = page.nextCursor
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    busy.value = false
  }
}

async function onExport() {
  const url = auditExportUrl(filterParams())
  const res = await fetch(url, { headers: authHeaders(), credentials: 'include' })
  if (!res.ok) {
    error.value = `导出失败 ${res.status}`
    return
  }
  const blob = await res.blob()
  const a = document.createElement('a')
  a.href = URL.createObjectURL(blob)
  a.download = 'audit.csv'
  a.click()
  URL.revokeObjectURL(a.href)
}

onMounted(reload)
</script>

<style scoped>
.mb { margin-bottom: 12px; }
.muted { color: var(--muted); font-size: 0.85rem; }
.more { margin-top: 12px; text-align: center; }
</style>
