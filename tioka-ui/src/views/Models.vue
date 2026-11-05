<template>
  <div class="page">
    <h1>模型 / 路由</h1>
    <p class="muted">可重复创建 Provider / LogicalModel / Binding / RoutingPlan，并发布</p>

    <el-alert v-if="msg" :title="msg" type="success" show-icon class="mb" @close="msg = ''" />
    <el-alert v-if="error" :title="error" type="error" show-icon class="mb" @close="error = ''" />

    <el-card shadow="never" class="mb">
      <template #header>
        <div class="card-head">
          <span>Providers</span>
          <el-button type="primary" size="small" @click="createProvider" :loading="busy">创建</el-button>
        </div>
      </template>
      <el-form :inline="true" :model="providerForm" class="mb">
        <el-form-item label="名称">
          <el-input v-model="providerForm.name" placeholder="mock" />
        </el-form-item>
        <el-form-item label="baseUrl">
          <el-input v-model="providerForm.baseUrl" placeholder="mock://openai" style="width: 200px" />
        </el-form-item>
        <el-form-item label="apiKey">
          <el-input v-model="providerForm.apiKeyPlain" placeholder="sk-…" show-password />
        </el-form-item>
      </el-form>
      <el-table :data="providers" size="small" stripe>
        <el-table-column prop="id" label="ID" min-width="140" />
        <el-table-column prop="name" label="名称" width="120" />
        <el-table-column prop="baseUrl" label="baseUrl" min-width="160" />
        <el-table-column prop="apiKeyRef" label="密钥引用" min-width="120" />
      </el-table>
    </el-card>

    <el-card shadow="never" class="mb">
      <template #header>
        <div class="card-head">
          <span>LogicalModel + Binding + Plan</span>
          <el-button type="primary" size="small" @click="createChain" :loading="busy">创建链路</el-button>
        </div>
      </template>
      <el-form label-width="120px" :model="chainForm">
        <el-form-item label="逻辑模型名">
          <el-input v-model="chainForm.modelName" />
        </el-form-item>
        <el-form-item label="能力标签">
          <el-input v-model="chainForm.capabilityTags" placeholder="tool" />
        </el-form-item>
        <el-form-item label="Provider">
          <el-select v-model="chainForm.providerId" placeholder="选择 Provider" style="width: 100%">
            <el-option v-for="p in providers" :key="p.id" :label="`${p.name} (${p.id})`" :value="p.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="绑定模型名">
          <el-input v-model="chainForm.bindingModelName" />
        </el-form-item>
        <el-form-item label="计划名">
          <el-input v-model="chainForm.planName" />
        </el-form-item>
        <el-form-item label="类型">
          <el-select v-model="chainForm.type" style="width: 160px">
            <el-option label="FIXED" value="FIXED" />
            <el-option label="FALLBACK" value="FALLBACK" />
          </el-select>
        </el-form-item>
        <el-form-item label="创建后发布">
          <el-switch v-model="chainForm.publish" />
        </el-form-item>
      </el-form>
      <el-divider />
      <el-button @click="bootstrap" :loading="busy">一键 Bootstrap（兼容旧流程）</el-button>
    </el-card>

    <el-card shadow="never">
      <template #header>Routing Plans</template>
      <el-table :data="plans" size="small" stripe>
        <el-table-column prop="id" label="ID" min-width="140" />
        <el-table-column prop="name" label="名称" width="140" />
        <el-table-column prop="type" label="类型" width="100" />
        <el-table-column prop="primaryBindingId" label="Primary Binding" min-width="140" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.published ? 'success' : 'info'" size="small">
              {{ row.published ? '已发布' : '草稿' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="100">
          <template #default="{ row }">
            <el-button
              v-if="!row.published"
              link
              type="primary"
              size="small"
              @click="publishPlan(row.id)"
            >
              发布
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { api } from '../api'

const providers = ref([])
const plans = ref([])
const busy = ref(false)
const msg = ref('')
const error = ref('')

const providerForm = reactive({
  name: 'mock',
  baseUrl: 'mock://openai',
  apiKeyPlain: 'sk-test'
})

const chainForm = reactive({
  modelName: 'gpt-mock',
  capabilityTags: 'tool',
  providerId: '',
  bindingModelName: 'gpt-mock',
  planName: 'fixed',
  type: 'FIXED',
  publish: true
})

async function refresh() {
  providers.value = await api('/api/model/providers')
  plans.value = await api('/api/model/routing-plans')
  if (!chainForm.providerId && providers.value.length) {
    chainForm.providerId = providers.value[0].id
  }
}

async function createProvider() {
  busy.value = true
  error.value = ''
  msg.value = ''
  try {
    const p = await api('/api/model/providers', {
      method: 'POST',
      body: JSON.stringify(providerForm)
    })
    msg.value = `Provider 已创建 ${p.id}`
    await refresh()
    chainForm.providerId = p.id
  } catch (e) {
    error.value = e.message
  } finally {
    busy.value = false
  }
}

async function createChain() {
  if (!chainForm.providerId) {
    error.value = '请先选择 Provider'
    return
  }
  busy.value = true
  error.value = ''
  msg.value = ''
  try {
    const lm = await api('/api/model/logical-models', {
      method: 'POST',
      body: JSON.stringify({ name: chainForm.modelName, capabilityTags: chainForm.capabilityTags })
    })
    const binding = await api('/api/model/bindings', {
      method: 'POST',
      body: JSON.stringify({
        logicalModelId: lm,
        providerId: chainForm.providerId,
        modelName: chainForm.bindingModelName
      })
    })
    let plan = await api('/api/model/routing-plans', {
      method: 'POST',
      body: JSON.stringify({
        name: chainForm.planName,
        type: chainForm.type,
        primaryBindingId: binding
      })
    })
    if (chainForm.publish) {
      plan = await api(`/api/model/routing-plans/${plan.id}/publish`, { method: 'POST', body: '{}' })
    }
    msg.value = `链路完成 plan=${plan.id}${plan.published ? '（已发布）' : ''}`
    await refresh()
  } catch (e) {
    error.value = e.message
  } finally {
    busy.value = false
  }
}

async function publishPlan(id) {
  try {
    await api(`/api/model/routing-plans/${id}/publish`, { method: 'POST', body: '{}' })
    msg.value = `已发布 ${id}`
    await refresh()
  } catch (e) {
    error.value = e.message
  }
}

async function bootstrap() {
  busy.value = true
  error.value = ''
  msg.value = ''
  try {
    const provider = await api('/api/model/providers', {
      method: 'POST',
      body: JSON.stringify({
        name: providerForm.name,
        baseUrl: providerForm.baseUrl,
        apiKeyPlain: providerForm.apiKeyPlain
      })
    })
    chainForm.providerId = provider.id
    await createChain()
  } catch (e) {
    error.value = e.message
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
