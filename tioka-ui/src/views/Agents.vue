<template>
  <div class="page">
    <h1>Agent 配置</h1>
    <p class="muted">创建 → 绑定路由 / MCP / Skill → 发布版本</p>

    <el-alert v-if="msg" :title="msg" type="success" show-icon class="mb" @close="msg = ''" />
    <el-alert v-if="error" :title="error" type="error" show-icon class="mb" @close="error = ''" />

    <el-card shadow="never" class="mb">
      <template #header>
        <div class="card-head">
          <span>创建 Agent</span>
        </div>
      </template>
      <el-form :inline="true" @submit.prevent>
        <el-form-item label="名称">
          <el-input v-model="name" placeholder="Agent 名称" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="create">创建</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card shadow="never" class="mb">
      <template #header>已有 Agent</template>
      <el-table :data="agents" size="small" stripe highlight-current-row @current-change="onPick">
        <el-table-column prop="id" label="ID" min-width="140" />
        <el-table-column prop="name" label="名称" width="160" />
        <el-table-column label="已发布版本" min-width="160">
          <template #default="{ row }">
            {{ row.publishedVersionId || '未发布' }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="100">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="pick(row)">编辑</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card v-if="agentId" shadow="never">
      <template #header>草稿配置 · {{ agentId }}</template>
      <el-form label-width="120px">
        <el-form-item label="系统指令">
          <el-input v-model="instructions" type="textarea" :rows="4" />
        </el-form-item>
        <el-form-item label="RoutingPlan">
          <el-select v-model="routingPlanId" placeholder="已发布 RoutingPlan" style="width: 100%">
            <el-option
              v-for="p in plans"
              :key="p.id"
              :label="`${p.name} (${p.type})`"
              :value="p.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="MCP Grants">
          <el-checkbox-group v-model="mcpGrantIds">
            <el-checkbox v-for="g in grants" :key="g.id" :label="g.id" :value="g.id">
              {{ g.id }} → agent {{ g.agentId }} / server {{ g.serverId }}
            </el-checkbox>
          </el-checkbox-group>
        </el-form-item>
        <el-form-item label="已发布 Skill">
          <el-select
            v-model="skillIds"
            multiple
            filterable
            clearable
            placeholder="多选已发布 Skill"
            style="width: 100%"
          >
            <el-option
              v-for="s in publishedSkills"
              :key="s.id"
              :label="s.name"
              :value="s.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :disabled="!routingPlanId" :loading="busy" @click="savePublish">
            保存并发布
          </el-button>
        </el-form-item>
      </el-form>
    </el-card>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { api } from '../api'

const name = ref('PoC Agent')
const agentId = ref('')
const instructions = ref('you are helpful')
const routingPlanId = ref('')
const mcpGrantIds = ref([])
const skillIds = ref([])
const agents = ref([])
const plans = ref([])
const grants = ref([])
const skills = ref([])
const msg = ref('')
const error = ref('')
const busy = ref(false)

const publishedSkills = computed(() => skills.value.filter((s) => s.published))

async function refresh() {
  agents.value = await api('/api/agents')
  plans.value = (await api('/api/model/routing-plans')).filter((p) => p.published)
  grants.value = await api('/api/mcp/grants')
  skills.value = await api('/api/skills')
}

async function create() {
  const a = await api('/api/agents', {
    method: 'POST',
    body: JSON.stringify({ name: name.value, workspaceId: 'ws_default' })
  })
  agentId.value = a.id
  await refresh()
}

function pick(a) {
  agentId.value = a.id
  name.value = a.name
}

function onPick(row) {
  if (row) pick(row)
}

async function savePublish() {
  msg.value = ''
  error.value = ''
  busy.value = true
  try {
    const draft = await api(`/api/agents/${agentId.value}/drafts`, {
      method: 'POST',
      body: JSON.stringify({
        instructions: instructions.value,
        routingPlanId: routingPlanId.value,
        mcpGrantIds: mcpGrantIds.value,
        skillIds: skillIds.value
      })
    })
    const pub = await api(`/api/agents/versions/${draft.id}/publish`, { method: 'POST', body: '{}' })
    msg.value = `已发布 ${pub.id} hash=${pub.snapshotHash} skills=${(pub.skillIds || []).join(',')}`
    await refresh()
  } catch (e) {
    error.value = e.message
  } finally {
    busy.value = false
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
