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
          <el-button @click="onExport">导出 CSV</el-button>
        </el-form-item>
      </el-form>
      <p class="muted">默认近 7 天（后端缺省）；可按操作人 / 动作 / 结果筛选。</p>
      <el-table :data="rows" size="small" stripe v-loading="busy">
        <el-table-column prop="ts" label="时间" min-width="170" />
        <el-table-column prop="actorId" label="操作人" width="100" />
        <el-table-column prop="action" label="动作" min-width="140" />
        <el-table-column prop="resourceType" label="资源类型" width="110" />
        <el-table-column prop="resourceId" label="资源 ID" min-width="120" />
        <el-table-column prop="outcome" label="结果" width="90" />
        <el-table-column prop="reason" label="原因" min-width="120" />
      </el-table>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { auditExportUrl, queryAudit, type AuditEvent } from '@/admin/adminApi'
import { authHeaders } from '@/api/http'

const rows = ref<AuditEvent[]>([])
const busy = ref(false)
const error = ref('')
const filters = reactive({ actorId: '', action: '', outcome: '' })

function params() {
  return {
    actorId: filters.actorId || undefined,
    action: filters.action || undefined,
    outcome: filters.outcome || undefined,
    page: '1',
    size: '50'
  }
}

async function reload() {
  busy.value = true
  error.value = ''
  try {
    rows.value = await queryAudit(params())
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    busy.value = false
  }
}

async function onExport() {
  const url = auditExportUrl(params())
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
</style>
