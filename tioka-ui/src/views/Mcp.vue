<template>
  <div class="page">
    <h1>MCP 管理</h1>
    <p class="muted">登记 Server → 刷新工具 → 授权 Agent（可重复操作）</p>

    <el-alert v-if="msg" :title="msg" type="success" show-icon class="mb" @close="msg = ''" />
    <el-alert v-if="error" :title="error" type="error" show-icon class="mb" @close="error = ''" />

    <el-card shadow="never" class="mb">
      <template #header>
        <div class="card-head">
          <span>登记 Server</span>
          <el-button type="primary" size="small" :loading="busy" @click="register">登记并刷新工具</el-button>
        </div>
      </template>
      <el-form :inline="true" :model="form">
        <el-form-item label="名称">
          <el-input v-model="form.name" />
        </el-form-item>
        <el-form-item label="baseUrl">
          <el-input v-model="form.baseUrl" style="width: 200px" />
        </el-form-item>
        <el-form-item label="Bearer">
          <el-input v-model="form.bearerToken" show-password />
        </el-form-item>
      </el-form>
    </el-card>

    <el-card shadow="never" class="mb">
      <template #header>已登记 Servers</template>
      <el-table :data="servers" size="small" stripe highlight-current-row @current-change="onServerPick">
        <el-table-column prop="id" label="ID" min-width="140" />
        <el-table-column prop="name" label="名称" width="140" />
        <el-table-column prop="baseUrl" label="baseUrl" min-width="180" />
        <el-table-column label="操作" width="160">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="loadServer(row)">查看工具</el-button>
            <el-button link size="small" @click="refreshTools(row)">刷新</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card v-if="server" shadow="never" class="mb">
      <template #header>工具预览 · {{ server.name }} ({{ server.id }})</template>
      <el-table :data="tools" size="small" stripe class="mb">
        <el-table-column label="选用" width="72">
          <template #default="{ row }">
            <el-checkbox
              :model-value="allowlist.includes(row.name)"
              @change="(v) => toggleAllow(row.name, !!v)"
            />
          </template>
        </el-table-column>
        <el-table-column prop="name" label="名称" min-width="120" />
        <el-table-column prop="description" label="描述" min-width="180" />
        <el-table-column label="风险" width="170">
          <template #default="{ row }">
            <el-select
              :model-value="row.risk || 'WRITE'"
              size="small"
              :disabled="riskBusy === row.name"
              @change="(v) => onRiskChange(row, v)"
            >
              <el-option v-for="r in MCP_TOOL_RISKS" :key="r" :label="r" :value="r" />
            </el-select>
          </template>
        </el-table-column>
      </el-table>
      <el-divider />
      <el-form :inline="true">
        <el-form-item label="授权给 Agent">
          <el-select v-model="agentId" placeholder="选择 Agent" style="width: 220px">
            <el-option v-for="a in agents" :key="a.id" :label="a.name" :value="a.id" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button
            type="primary"
            :disabled="!agentId || !allowlist.length"
            @click="grant"
          >
            创建 Grant
          </el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card shadow="never">
      <template #header>Grants</template>
      <el-table :data="grants" size="small" stripe>
        <el-table-column prop="id" label="ID" min-width="140" />
        <el-table-column prop="agentId" label="Agent" min-width="120" />
        <el-table-column prop="serverId" label="Server" min-width="120" />
        <el-table-column label="Allowlist" min-width="200">
          <template #default="{ row }">
            {{ (row.allowlist || []).join(', ') }}
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { api } from '../api'
import { MCP_TOOL_RISKS, patchToolRisk } from './mcpToolRisk'

const form = reactive({ name: 'mock-mcp', baseUrl: 'mock://mcp', bearerToken: 'tok' })
const servers = ref([])
const agents = ref([])
const grants = ref([])
const server = ref(null)
const tools = ref([])
const allowlist = ref([])
const agentId = ref('')
const busy = ref(false)
const riskBusy = ref('')
const msg = ref('')
const error = ref('')

async function refresh() {
  servers.value = await api('/api/mcp/servers')
  agents.value = await api('/api/agents')
  grants.value = await api('/api/mcp/grants')
}

async function register() {
  busy.value = true
  error.value = ''
  msg.value = ''
  try {
    server.value = await api('/api/mcp/servers', {
      method: 'POST',
      body: JSON.stringify(form)
    })
    tools.value = await api(`/api/mcp/servers/${server.value.id}/refresh-tools`, {
      method: 'POST',
      body: '{}'
    })
    allowlist.value = tools.value.map((t) => t.name)
    msg.value = `已登记 ${server.value.id}`
    await refresh()
  } catch (e) {
    error.value = e.message
  } finally {
    busy.value = false
  }
}

async function loadServer(s) {
  server.value = s
  tools.value = await api(`/api/mcp/servers/${s.id}/tools`)
  allowlist.value = tools.value.map((t) => t.name)
}

function onServerPick(row) {
  if (row) loadServer(row)
}

async function refreshTools(s) {
  tools.value = await api(`/api/mcp/servers/${s.id}/refresh-tools`, { method: 'POST', body: '{}' })
  allowlist.value = tools.value.map((t) => t.name)
  server.value = s
  msg.value = `已刷新 ${s.id}`
}

function toggleAllow(name, checked) {
  if (checked) {
    if (!allowlist.value.includes(name)) allowlist.value = [...allowlist.value, name]
  } else {
    allowlist.value = allowlist.value.filter((n) => n !== name)
  }
}

async function onRiskChange(row, risk) {
  if (!server.value || !row?.name) return
  const prev = row.risk || 'WRITE'
  if (prev === risk) return
  riskBusy.value = row.name
  error.value = ''
  row.risk = risk
  try {
    const updated = await patchToolRisk(api, server.value.id, row.name, risk)
    row.risk = updated.risk
    msg.value = `${row.name} 风险已更新为 ${updated.risk}`
  } catch (e) {
    row.risk = prev
    error.value = e.message
  } finally {
    riskBusy.value = ''
  }
}

async function grant() {
  try {
    const g = await api('/api/mcp/grants', {
      method: 'POST',
      body: JSON.stringify({
        agentId: agentId.value,
        serverId: server.value.id,
        allowlist: allowlist.value
      })
    })
    msg.value = `Grant 已创建：${g.id}`
    await refresh()
  } catch (e) {
    error.value = e.message
  }
}

onMounted(refresh)
</script>

<style scoped>
.page { max-width: 960px; display: grid; gap: 8px; }
.muted { color: var(--muted); margin: 0 0 8px; }
.mb { margin-bottom: 12px; }
.card-head { display: flex; justify-content: space-between; align-items: center; }
</style>
