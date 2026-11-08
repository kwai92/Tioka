<template>
  <div class="page">
    <h1>模型 / 路由</h1>
    <p class="muted">Provider、Binding（V117 字段）、故障接力链与各用途默认方案</p>

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
          <span>Bindings</span>
          <el-button type="primary" size="small" @click="createBindingRow" :loading="busy">创建</el-button>
        </div>
      </template>
      <el-form label-width="140px" :model="bindingForm" class="mb">
        <el-form-item label="逻辑模型名">
          <el-input v-model="bindingForm.modelName" placeholder="会先创建 LogicalModel" />
        </el-form-item>
        <el-form-item label="能力标签">
          <el-input v-model="bindingForm.capabilityTags" placeholder="tool" />
        </el-form-item>
        <el-form-item label="Provider">
          <el-select v-model="bindingForm.providerId" placeholder="选择 Provider" style="width: 100%">
            <el-option v-for="p in providers" :key="p.id" :label="`${p.name} (${p.id})`" :value="p.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="绑定模型名">
          <el-input v-model="bindingForm.bindingModelName" />
        </el-form-item>
        <el-form-item label="kind">
          <el-select v-model="bindingForm.kind" style="width: 160px">
            <el-option v-for="k in BINDING_KINDS" :key="k" :label="k" :value="k" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="bindingForm.kind === 'embedding'" label="embeddingDim">
          <el-input-number v-model="bindingForm.embeddingDim" :min="1" />
        </el-form-item>
        <el-form-item label="contextWindow">
          <el-input-number v-model="bindingForm.contextWindow" :min="0" />
        </el-form-item>
        <el-form-item label="输入价 (micros/1M)">
          <el-input-number v-model="bindingForm.inputPriceMicros" :min="0" />
        </el-form-item>
        <el-form-item label="输出价 (micros/1M)">
          <el-input-number v-model="bindingForm.outputPriceMicros" :min="0" />
        </el-form-item>
        <el-form-item label="RPM（空=不限）">
          <el-input-number v-model="bindingForm.rpmLimit" :min="0" />
        </el-form-item>
        <el-form-item label="TPM（空=不限）">
          <el-input-number v-model="bindingForm.tpmLimit" :min="0" />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="bindingForm.enabled" />
        </el-form-item>
      </el-form>
      <el-table :data="bindings" size="small" stripe>
        <el-table-column prop="id" label="ID" min-width="120" />
        <el-table-column prop="modelName" label="模型" width="120" />
        <el-table-column prop="kind" label="kind" width="90" />
        <el-table-column prop="inputPriceMicros" label="入价" width="80" />
        <el-table-column prop="outputPriceMicros" label="出价" width="80" />
        <el-table-column prop="rpmLimit" label="RPM" width="70" />
        <el-table-column prop="tpmLimit" label="TPM" width="70" />
        <el-table-column label="启用" width="90">
          <template #default="{ row }">
            <el-switch
              :model-value="row.enabled"
              @change="(v: boolean) => toggleBinding(row, v)"
            />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="100">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="editOpen" title="编辑 Binding" width="480px" destroy-on-close>
      <el-form v-if="editForm" label-width="140px">
        <el-form-item label="kind">
          <el-select v-model="editForm.kind" style="width: 160px">
            <el-option v-for="k in BINDING_KINDS" :key="k" :label="k" :value="k" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="editForm.kind === 'embedding'" label="embeddingDim">
          <el-input-number v-model="editForm.embeddingDim" :min="1" />
        </el-form-item>
        <el-form-item label="contextWindow">
          <el-input-number v-model="editForm.contextWindow" :min="0" />
        </el-form-item>
        <el-form-item label="输入价 (micros/1M)">
          <el-input-number v-model="editForm.inputPriceMicros" :min="0" />
        </el-form-item>
        <el-form-item label="输出价 (micros/1M)">
          <el-input-number v-model="editForm.outputPriceMicros" :min="0" />
        </el-form-item>
        <el-form-item label="RPM">
          <el-input-number v-model="editForm.rpmLimit" :min="0" />
        </el-form-item>
        <el-form-item label="TPM">
          <el-input-number v-model="editForm.tpmLimit" :min="0" />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="editForm.enabled" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editOpen = false">取消</el-button>
        <el-button type="primary" :loading="busy" @click="saveEdit">保存</el-button>
      </template>
    </el-dialog>

    <el-card shadow="never" class="mb">
      <template #header>
        <div class="card-head">
          <span>Routing Plans（故障接力）</span>
          <el-button type="primary" size="small" @click="createPlan" :loading="busy">创建方案</el-button>
        </div>
      </template>
      <el-form label-width="140px" :model="planForm" class="mb">
        <el-form-item label="计划名">
          <el-input v-model="planForm.name" />
        </el-form-item>
        <el-form-item label="类型">
          <el-select v-model="planForm.type" style="width: 200px">
            <el-option label="FIXED" value="FIXED" />
            <el-option label="PRIMARY_FALLBACK" value="PRIMARY_FALLBACK" />
            <el-option label="HARD" value="HARD" />
          </el-select>
        </el-form-item>
        <el-form-item label="Primary Binding">
          <el-select v-model="planForm.primaryBindingId" style="width: 100%" filterable>
            <el-option
              v-for="b in bindings"
              :key="b.id"
              :label="`${b.modelName} (${b.kind}) ${b.id}`"
              :value="b.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item v-if="planForm.type === 'HARD'" label="Hard Binding">
          <el-select v-model="planForm.hardBindingId" style="width: 100%" filterable clearable>
            <el-option
              v-for="b in bindings"
              :key="b.id"
              :label="`${b.modelName} (${b.id})`"
              :value="b.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item v-if="planForm.type === 'PRIMARY_FALLBACK'" label="故障接力链">
          <el-select
            v-model="planForm.fallbackIds"
            multiple
            filterable
            style="width: 100%"
            placeholder="有序备用 binding（不含 primary）"
          >
            <el-option
              v-for="b in fallbackCandidates"
              :key="b.id"
              :label="`${b.modelName} (${b.id})`"
              :value="b.id"
            />
          </el-select>
          <div class="muted small">{{ failoverPreview }}</div>
        </el-form-item>
        <el-form-item label="创建后发布">
          <el-switch v-model="planForm.publish" />
        </el-form-item>
      </el-form>
      <el-table :data="plans" size="small" stripe>
        <el-table-column prop="id" label="ID" min-width="120" />
        <el-table-column prop="name" label="名称" width="120" />
        <el-table-column prop="type" label="类型" width="140" />
        <el-table-column prop="primaryBindingId" label="Primary" min-width="120" />
        <el-table-column label="接力链" min-width="160">
          <template #default="{ row }">
            {{ row.fallbackChain || row.fallbackBindingId || '—' }}
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.published ? 'success' : 'info'" size="small">
              {{ row.published ? '已发布' : '草稿' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="90">
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

    <el-card shadow="never">
      <template #header>
        <div class="card-head">
          <span>各用途默认方案</span>
          <el-button type="primary" size="small" @click="saveDefaults" :loading="busy">保存全部</el-button>
        </div>
      </template>
      <el-table :data="defaultRows" size="small" stripe>
        <el-table-column prop="purpose" label="用途" width="120" />
        <el-table-column label="路由方案" min-width="220">
          <template #default="{ row }">
            <el-select v-model="row.routingPlanId" clearable filterable placeholder="已发布方案" style="width: 100%">
              <el-option
                v-for="p in publishedPlans"
                :key="p.id"
                :label="`${p.name} (${p.id})`"
                :value="p.id"
              />
            </el-select>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { api } from '../api'
import {
  BINDING_KINDS,
  DEFAULT_PLAN_PURPOSES,
  type BindingView,
  type DefaultPlanPurpose,
  type RoutingPlanView,
  createBinding,
  createRoutingPlan,
  formatFailoverPreview,
  joinFallbackChain,
  listBindings,
  listDefaultPlans,
  patchBinding,
  putDefaultPlan
} from './modelConsole'

type ProviderView = { id: string; name: string; baseUrl: string; apiKeyRef: string }

const providers = ref<ProviderView[]>([])
const bindings = ref<BindingView[]>([])
const plans = ref<RoutingPlanView[]>([])
const busy = ref(false)
const msg = ref('')
const error = ref('')

const providerForm = ref({
  name: 'mock',
  baseUrl: 'mock://openai',
  apiKeyPlain: 'sk-test'
})

const bindingForm = ref({
  modelName: 'gpt-mock',
  capabilityTags: 'tool',
  providerId: '',
  bindingModelName: 'gpt-mock',
  kind: 'chat' as string,
  embeddingDim: 1024 as number | undefined,
  contextWindow: undefined as number | undefined,
  inputPriceMicros: 0,
  outputPriceMicros: 0,
  rpmLimit: undefined as number | undefined,
  tpmLimit: undefined as number | undefined,
  enabled: true
})

const planForm = ref({
  name: 'primary-fallback',
  type: 'PRIMARY_FALLBACK',
  primaryBindingId: '',
  hardBindingId: '' as string,
  fallbackIds: [] as string[],
  publish: true
})

const editOpen = ref(false)
const editId = ref('')
const editForm = ref<{
  kind: string
  embeddingDim?: number
  contextWindow?: number
  inputPriceMicros: number
  outputPriceMicros: number
  rpmLimit?: number
  tpmLimit?: number
  enabled: boolean
} | null>(null)

type DefaultRow = { purpose: DefaultPlanPurpose; routingPlanId: string }
const defaultRows = ref<DefaultRow[]>(
  DEFAULT_PLAN_PURPOSES.map((purpose) => ({ purpose, routingPlanId: '' }))
)

const publishedPlans = computed(() => plans.value.filter((p) => p.published))
const fallbackCandidates = computed(() =>
  bindings.value.filter((b) => b.id !== planForm.value.primaryBindingId)
)
const failoverPreview = computed(() =>
  formatFailoverPreview(planForm.value.primaryBindingId, planForm.value.fallbackIds)
)

async function refresh() {
  providers.value = await api('/api/model/providers')
  bindings.value = await listBindings(api)
  plans.value = await api('/api/model/routing-plans')
  const defs = await listDefaultPlans(api)
  const byPurpose = new Map(defs.map((d) => [d.purpose, d.routingPlanId || '']))
  defaultRows.value = DEFAULT_PLAN_PURPOSES.map((purpose) => ({
    purpose,
    routingPlanId: byPurpose.get(purpose) || ''
  }))
  if (!bindingForm.value.providerId && providers.value.length) {
    bindingForm.value.providerId = providers.value[0].id
  }
  if (!planForm.value.primaryBindingId && bindings.value.length) {
    planForm.value.primaryBindingId = bindings.value[0].id
  }
}

async function createProvider() {
  busy.value = true
  error.value = ''
  msg.value = ''
  try {
    const p = await api<ProviderView>('/api/model/providers', {
      method: 'POST',
      body: JSON.stringify(providerForm.value)
    })
    msg.value = `Provider 已创建 ${p.id}`
    await refresh()
    bindingForm.value.providerId = p.id
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    busy.value = false
  }
}

async function createBindingRow() {
  if (!bindingForm.value.providerId) {
    error.value = '请先选择 Provider'
    return
  }
  if (bindingForm.value.kind === 'embedding' && !bindingForm.value.embeddingDim) {
    error.value = 'kind=embedding 时 embeddingDim 必填'
    return
  }
  busy.value = true
  error.value = ''
  msg.value = ''
  try {
    const lm = await api<string>('/api/model/logical-models', {
      method: 'POST',
      body: JSON.stringify({
        name: bindingForm.value.modelName,
        capabilityTags: bindingForm.value.capabilityTags
      })
    })
    const id = await createBinding(api, {
      logicalModelId: lm,
      providerId: bindingForm.value.providerId,
      modelName: bindingForm.value.bindingModelName,
      kind: bindingForm.value.kind,
      embeddingDim: bindingForm.value.kind === 'embedding' ? bindingForm.value.embeddingDim : null,
      contextWindow: bindingForm.value.contextWindow ?? null,
      inputPriceMicros: bindingForm.value.inputPriceMicros,
      outputPriceMicros: bindingForm.value.outputPriceMicros,
      rpmLimit: bindingForm.value.rpmLimit ?? null,
      tpmLimit: bindingForm.value.tpmLimit ?? null,
      enabled: bindingForm.value.enabled
    })
    msg.value = `Binding 已创建 ${id}`
    await refresh()
    planForm.value.primaryBindingId = id
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    busy.value = false
  }
}

function openEdit(row: BindingView) {
  editId.value = row.id
  editForm.value = {
    kind: row.kind,
    embeddingDim: row.embeddingDim ?? undefined,
    contextWindow: row.contextWindow ?? undefined,
    inputPriceMicros: row.inputPriceMicros,
    outputPriceMicros: row.outputPriceMicros,
    rpmLimit: row.rpmLimit ?? undefined,
    tpmLimit: row.tpmLimit ?? undefined,
    enabled: row.enabled
  }
  editOpen.value = true
}

async function saveEdit() {
  if (!editForm.value || !editId.value) return
  busy.value = true
  error.value = ''
  try {
    await patchBinding(api, editId.value, {
      kind: editForm.value.kind,
      embeddingDim: editForm.value.kind === 'embedding' ? editForm.value.embeddingDim ?? null : null,
      contextWindow: editForm.value.contextWindow ?? null,
      inputPriceMicros: editForm.value.inputPriceMicros,
      outputPriceMicros: editForm.value.outputPriceMicros,
      rpmLimit: editForm.value.rpmLimit ?? null,
      tpmLimit: editForm.value.tpmLimit ?? null,
      enabled: editForm.value.enabled
    })
    msg.value = `已更新 ${editId.value}`
    editOpen.value = false
    await refresh()
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    busy.value = false
  }
}

async function toggleBinding(row: BindingView, enabled: boolean) {
  try {
    await patchBinding(api, row.id, { enabled })
    row.enabled = enabled
    msg.value = `${row.id} ${enabled ? '已启用' : '已停用'}`
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : String(e)
    await refresh()
  }
}

async function createPlan() {
  if (!planForm.value.primaryBindingId) {
    error.value = '请选择 Primary Binding'
    return
  }
  busy.value = true
  error.value = ''
  msg.value = ''
  try {
    const chain =
      planForm.value.type === 'PRIMARY_FALLBACK'
        ? joinFallbackChain(planForm.value.fallbackIds)
        : null
    let plan = await createRoutingPlan(api, {
      name: planForm.value.name,
      type: planForm.value.type,
      primaryBindingId: planForm.value.primaryBindingId,
      hardBindingId:
        planForm.value.type === 'HARD' ? planForm.value.hardBindingId || null : null,
      fallbackChain: chain
    })
    if (planForm.value.publish) {
      plan = await api(`/api/model/routing-plans/${plan.id}/publish`, {
        method: 'POST',
        body: '{}'
      })
    }
    msg.value = `方案完成 ${plan.id}${plan.published ? '（已发布）' : ''}`
    await refresh()
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    busy.value = false
  }
}

async function publishPlan(id: string) {
  try {
    await api(`/api/model/routing-plans/${id}/publish`, { method: 'POST', body: '{}' })
    msg.value = `已发布 ${id}`
    await refresh()
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : String(e)
  }
}

async function saveDefaults() {
  busy.value = true
  error.value = ''
  msg.value = ''
  try {
    for (const row of defaultRows.value) {
      await putDefaultPlan(api, row.purpose, {
        routingPlanId: row.routingPlanId || null
      })
    }
    msg.value = '默认方案已保存'
    await refresh()
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    busy.value = false
  }
}

onMounted(refresh)
</script>

<style scoped>
.page { max-width: 1040px; display: grid; gap: 8px; }
.muted { color: var(--muted); margin: 0 0 8px; }
.small { font-size: 12px; margin-top: 6px; }
.mb { margin-bottom: 12px; }
.card-head { display: flex; justify-content: space-between; align-items: center; }
</style>
