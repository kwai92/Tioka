<template>
  <div class="studio">
    <h1>Flow Studio</h1>

    <el-alert
      v-if="uiState === 'empty'"
      type="info"
      show-icon
      :closable="false"
      title="空图：从节点库添加节点，连线后校验 / 发布"
      class="mb"
    />
    <el-alert
      v-else-if="uiState === 'error'"
      type="error"
      show-icon
      :closable="false"
      title="校验未通过"
      class="mb"
    >
      <ul class="issues">
        <li v-for="(iss, i) in issues" :key="i">
          <template v-if="iss.nodeId">[{{ iss.nodeId }}] </template>{{ iss.message }}
        </li>
      </ul>
    </el-alert>
    <el-alert
      v-else-if="uiState === 'ok'"
      type="success"
      show-icon
      :closable="true"
      :title="statusText"
      class="mb"
      @close="statusText = '画布就绪'"
    />

    <el-alert
      v-if="warnings.length"
      type="warning"
      show-icon
      :closable="false"
      title="警告（未知节点类型）"
      class="mb"
    >
      <ul class="issues">
        <li v-for="(w, i) in warnings" :key="'w' + i">
          [{{ w.nodeId }}] {{ w.message }}
        </li>
      </ul>
    </el-alert>

    <div class="nodes">
      <button v-for="t in nodeTypes" :key="t" type="button" class="chip" @click="addNode(t)">+ {{ t }}</button>
      <button type="button" class="chip ghost" @click="loadDefault">加载示例</button>
      <button type="button" class="chip ghost" @click="clearCanvas">清空</button>
    </div>

    <div class="canvas-wrap">
      <VueFlow
        v-model:nodes="nodes"
        v-model:edges="edges"
        fit-view-on-init
        @connect="onConnect"
        @node-click="onNodeClick"
        @pane-click="selectedId = ''"
        @nodes-change="syncGraph"
        @edges-change="syncGraph"
      >
        <Background pattern-color="#334155" :gap="18" />
        <Controls />
      </VueFlow>
    </div>

    <el-card v-if="selectedId" shadow="never" class="mb config-card">
      <template #header>
        <span>节点配置 · {{ selectedId }}（{{ selectedType }}）</span>
      </template>

      <template v-if="selectedType === 'LLM'">
        <el-alert
          v-if="llmHasLegacy"
          type="warning"
          show-icon
          :closable="false"
          class="mb"
          title="直连模型字段已废弃"
          description="运行时经 ModelGateway；请改选路由方案，或留空使用 FLOW 默认方案（模型页）。下列旧字段只读，应用时不会回写。"
        />
        <el-descriptions
          v-if="llmHasLegacy"
          :column="1"
          size="small"
          border
          class="mb legacy-desc"
        >
          <el-descriptions-item v-for="(val, key) in llmLegacyFields" :key="key" :label="String(key)">
            {{ formatLegacyValue(val) }}
          </el-descriptions-item>
        </el-descriptions>
        <el-form label-width="120px" @submit.prevent>
          <el-form-item label="路由方案">
            <el-select
              v-model="llmRoutingPlanId"
              clearable
              filterable
              placeholder="留空 = FLOW 默认方案"
              style="width: 100%"
            >
              <el-option
                v-for="p in publishedPlans"
                :key="p.id"
                :label="`${p.name} (${p.type || '-'})`"
                :value="p.id"
              />
            </el-select>
          </el-form-item>
        </el-form>
        <div class="config-actions">
          <el-button size="small" type="primary" @click="applyLlmConfig">应用 config</el-button>
          <span v-if="configError" class="cfg-err">{{ configError }}</span>
        </div>
      </template>

      <template v-else>
        <el-input
          v-model="selectedConfigText"
          type="textarea"
          :rows="5"
          placeholder='{"field":"route","equals":"A"}'
          @change="applySelectedConfig"
        />
        <div class="config-actions">
          <el-button size="small" type="primary" @click="applySelectedConfig">应用 config</el-button>
          <span v-if="configError" class="cfg-err">{{ configError }}</span>
        </div>
      </template>
    </el-card>

    <details class="raw">
      <summary>图 JSON</summary>
      <textarea v-model="graph" rows="6" @change="loadFromJson"></textarea>
    </details>

    <div class="row">
      <el-button @click="doValidate">校验</el-button>
      <el-button type="primary" :loading="publishing" @click="publish">发布版本</el-button>
      <el-input v-model="toolName" placeholder="Tool 名称" style="width: 160px" />
      <el-button type="success" :disabled="!versionId" :loading="publishingTool" @click="publishAsTool">
        发布为 Tool
      </el-button>
      <el-button :disabled="!flowId" @click="openShare">分享</el-button>
    </div>
    <p v-if="flowId || versionId" class="muted">
      <template v-if="flowId">Flow：{{ flowId }}</template>
      <template v-if="flowId && versionId"> · </template>
      <template v-if="versionId">版本：{{ versionId }}</template>
    </p>

    <ResourceAclDialog v-model="shareOpen" resource-type="flow" :resource-id="flowId" />

    <el-card shadow="never" class="playground">
      <template #header>Playground</template>
      <p class="muted">编辑 input JSON，运行后逐步查看各节点 type / input / output</p>
      <el-input
        v-model="playgroundInput"
        type="textarea"
        :rows="4"
        class="mb"
        placeholder='{"route":"A"}'
      />
      <el-button type="primary" :disabled="!versionId" :loading="running" @click="runPlayground">
        运行
      </el-button>
      <p v-if="runMeta" class="muted run-meta">{{ runMeta }}</p>

      <div v-if="traceSteps.length" class="trace">
        <div v-for="(step, idx) in traceSteps" :key="idx" class="trace-step">
          <div class="trace-head">
            <el-tag size="small" type="info">#{{ idx + 1 }}</el-tag>
            <el-tag size="small">{{ step.type || 'unknown' }}</el-tag>
            <span v-if="step.nodeId" class="muted">{{ step.nodeId }}</span>
          </div>
          <div class="trace-io">
            <div>
              <div class="io-label">input</div>
              <pre>{{ formatJson(step.input) }}</pre>
            </div>
            <div>
              <div class="io-label">output</div>
              <pre>{{ formatJson(step.output) }}</pre>
            </div>
          </div>
        </div>
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { VueFlow } from '@vue-flow/core'
import { Background } from '@vue-flow/background'
import { Controls } from '@vue-flow/controls'
import { api } from '../api'
import ResourceAclDialog from '@/components/ResourceAclDialog.vue'
import {
  extractLegacyLlmDirectFields,
  filterPublishedRoutingPlans,
  hasLegacyLlmDirectFields,
  normalizeLlmNodeConfig
} from './flowLlmNode'
import '@vue-flow/core/dist/style.css'
import '@vue-flow/core/dist/theme-default.css'
import '@vue-flow/controls/dist/style.css'

/** @typedef {{ nodeId?: string, message: string, level: 'error'|'warn' }} Issue */
/** @typedef {{ type?: string, nodeId?: string, input?: unknown, output?: unknown }} TraceStep */
/** @typedef {{ id: string, name: string, type?: string, published?: boolean }} RoutingPlanOpt */

const uiState = ref('empty')
const statusText = ref('画布就绪')
/** @type {import('vue').Ref<Issue[]>} */
const issues = ref([])
/** @type {import('vue').Ref<Issue[]>} */
const warnings = ref([])
const nodeTypes = ref(/** @type {string[]} */ ([]))
const versionId = ref('')
const flowId = ref('')
const shareOpen = ref(false)
const toolName = ref('studio_flow_tool')

function openShare() {
  if (!flowId.value) {
    ElMessage.warning('请先发布版本以获得 Flow ID')
    return
  }
  shareOpen.value = true
}
const nodes = ref(/** @type {any[]} */ ([]))
const edges = ref(/** @type {any[]} */ ([]))
const graph = ref('')
const selectedId = ref('')
const selectedConfigText = ref('{}')
const configError = ref('')
const playgroundInput = ref('{\n  "route": "A"\n}')
/** @type {import('vue').Ref<TraceStep[]>} */
const traceSteps = ref([])
const runMeta = ref('')
const publishing = ref(false)
const publishingTool = ref(false)
const running = ref(false)
/** @type {import('vue').Ref<RoutingPlanOpt[]>} */
const publishedPlans = ref([])
const llmRoutingPlanId = ref(/** @type {string} */ (''))
const llmHasLegacy = ref(false)
/** @type {import('vue').Ref<Record<string, unknown>>} */
const llmLegacyFields = ref({})

const selectedType = computed(() => {
  const n = nodes.value.find((x) => x.id === selectedId.value)
  return n?.data?.type || ''
})

watch(selectedId, (id) => {
  configError.value = ''
  if (!id) {
    selectedConfigText.value = '{}'
    llmRoutingPlanId.value = ''
    llmHasLegacy.value = false
    llmLegacyFields.value = {}
    return
  }
  const n = nodes.value.find((x) => x.id === id)
  const cfg = n?.data?.config ?? {}
  selectedConfigText.value = JSON.stringify(cfg, null, 2)
  syncLlmFormFromConfig(cfg)
})

onMounted(async () => {
  try {
    nodeTypes.value = await api('/api/flows/node-types')
  } catch (e) {
    ElMessage.error(e?.message || '加载节点类型失败')
    nodeTypes.value = ['IO', 'LLM', 'Agent', 'Retrieve', 'HTTP', 'MCP', 'Branch', 'HumanCard']
  }
  try {
    const plans = await api('/api/model/routing-plans')
    publishedPlans.value = filterPublishedRoutingPlans(Array.isArray(plans) ? plans : [])
  } catch {
    publishedPlans.value = []
  }
  clearCanvas()
})

/**
 * @param {Record<string, unknown>} cfg
 */
function syncLlmFormFromConfig(cfg) {
  const c = cfg && typeof cfg === 'object' ? cfg : {}
  llmHasLegacy.value = hasLegacyLlmDirectFields(c)
  llmLegacyFields.value = extractLegacyLlmDirectFields(c)
  const rp = c.routingPlanId
  llmRoutingPlanId.value = rp == null || String(rp).trim() === '' ? '' : String(rp).trim()
}

function formatLegacyValue(val) {
  if (val == null) return '—'
  if (typeof val === 'string') return val
  try {
    return JSON.stringify(val)
  } catch {
    return String(val)
  }
}

function applyLlmConfig() {
  configError.value = ''
  if (!selectedId.value) return
  const n = nodes.value.find((x) => x.id === selectedId.value)
  const prev = n?.data?.config && typeof n.data.config === 'object' ? n.data.config : {}
  const next = normalizeLlmNodeConfig({
    ...prev,
    routingPlanId: llmRoutingPlanId.value
  })
  nodes.value = nodes.value.map((node) =>
    node.id === selectedId.value ? { ...node, data: { ...node.data, config: next } } : node
  )
  selectedConfigText.value = JSON.stringify(next, null, 2)
  syncLlmFormFromConfig(next)
  syncGraph()
  ElMessage.success('config 已更新')
}

function clearCanvas() {
  nodes.value = []
  edges.value = []
  selectedId.value = ''
  versionId.value = ''
  traceSteps.value = []
  runMeta.value = ''
  syncGraph()
  refreshUiState()
}

function loadDefault() {
  applyGraph({
    nodes: [
      { id: 'n1', type: 'IO' },
      { id: 'n2', type: 'Branch', config: { field: 'route', equals: 'A' } },
      { id: 'n3', type: 'LLM' },
      { id: 'n4', type: 'HTTP' }
    ],
    edges: [
      { from: 'n1', to: 'n2' },
      { from: 'n2', to: 'n3', when: 'true' },
      { from: 'n2', to: 'n4', when: 'false' }
    ]
  })
  ElMessage.success('已加载示例图')
}

function applyGraph(g) {
  nodes.value = (g.nodes || []).map((n, idx) => ({
    id: n.id,
    label: `${n.type}\n${n.id}`,
    position: { x: 40 + (idx % 4) * 180, y: 40 + Math.floor(idx / 4) * 110 },
    data: { type: n.type, config: n.config || {} }
  }))
  edges.value = (g.edges || []).map((e, i) => ({
    id: `e${i}`,
    source: e.from,
    target: e.to,
    label: e.when || ''
  }))
  syncGraph()
  refreshUiState()
}

function toDomainGraph() {
  return {
    nodes: nodes.value.map((n) => {
      const type = n.data?.type || 'IO'
      const raw = n.data?.config || {}
      return {
        id: n.id,
        type,
        config: type === 'LLM' ? normalizeLlmNodeConfig(raw) : raw
      }
    }),
    edges: edges.value.map((e) => {
      const edge = { from: e.source, to: e.target }
      if (e.label) edge.when = e.label
      return edge
    })
  }
}

function syncGraph() {
  graph.value = JSON.stringify(toDomainGraph(), null, 2)
  if (uiState.value !== 'error') refreshUiState()
}

function refreshUiState() {
  if (!nodes.value.length) {
    uiState.value = 'empty'
    issues.value = []
    warnings.value = []
    return
  }
  if (uiState.value === 'error') return
  uiState.value = 'ok'
  if (!statusText.value || statusText.value === '空图') statusText.value = '画布就绪'
}

function addNode(type) {
  const id = 'n' + (nodes.value.length + 1)
  nodes.value = [
    ...nodes.value,
    {
      id,
      label: `${type}\n${id}`,
      position: { x: 60 + nodes.value.length * 24, y: 60 + nodes.value.length * 16 },
      data: { type, config: {} }
    }
  ]
  selectedId.value = id
  syncGraph()
  uiState.value = 'ok'
  statusText.value = `已添加 ${type}`
}

function onConnect(params) {
  edges.value = [
    ...edges.value,
    {
      id: `e_${params.source}_${params.target}_${edges.value.length}`,
      source: params.source,
      target: params.target
    }
  ]
  syncGraph()
}

function onNodeClick({ node }) {
  selectedId.value = node.id
}

function applySelectedConfig() {
  configError.value = ''
  if (!selectedId.value) return
  try {
    const cfg = JSON.parse(selectedConfigText.value || '{}')
    if (cfg === null || typeof cfg !== 'object' || Array.isArray(cfg)) {
      throw new Error('config 必须是 JSON 对象')
    }
    nodes.value = nodes.value.map((n) =>
      n.id === selectedId.value ? { ...n, data: { ...n.data, config: cfg } } : n
    )
    syncGraph()
    ElMessage.success('config 已更新')
  } catch (e) {
    configError.value = e?.message || 'JSON 无效'
  }
}

function loadFromJson() {
  try {
    applyGraph(JSON.parse(graph.value))
    uiState.value = nodes.value.length ? 'ok' : 'empty'
    statusText.value = '已从 JSON 加载'
  } catch {
    uiState.value = 'error'
    issues.value = [{ message: '图 JSON 无法解析', level: 'error' }]
  }
}

/**
 * @returns {{ ok: boolean, errors: Issue[], warns: Issue[] }}
 */
function validateGraph() {
  const g = toDomainGraph()
  /** @type {Issue[]} */
  const errors = []
  /** @type {Issue[]} */
  const warns = []
  const known = new Set(nodeTypes.value)

  if (!g.nodes.length) {
    errors.push({ message: '至少需要一个节点', level: 'error' })
  }

  const ids = new Set(g.nodes.map((n) => n.id))
  for (const n of g.nodes) {
    if (!n.id) errors.push({ nodeId: '(missing)', message: '节点缺少 id', level: 'error' })
    if (!n.type) errors.push({ nodeId: n.id, message: '节点缺少 type', level: 'error' })
    else if (known.size && !known.has(n.type)) {
      warns.push({ nodeId: n.id, message: `未知节点类型: ${n.type}`, level: 'warn' })
    }
  }

  g.edges.forEach((e, i) => {
    if (!ids.has(e.from)) {
      errors.push({ nodeId: e.from, message: `边 #${i} source 不存在: ${e.from}`, level: 'error' })
    }
    if (!ids.has(e.to)) {
      errors.push({ nodeId: e.to, message: `边 #${i} target 不存在: ${e.to}`, level: 'error' })
    }
  })

  return { ok: errors.length === 0, errors, warns }
}

function doValidate() {
  const { ok, errors, warns } = validateGraph()
  issues.value = errors
  warnings.value = warns
  if (!ok) {
    uiState.value = nodes.value.length ? 'error' : 'empty'
    if (!nodes.value.length) issues.value = errors
    return false
  }
  uiState.value = 'ok'
  statusText.value = warns.length ? `校验通过（${warns.length} 条警告）` : '校验通过'
  ElMessage.success(statusText.value)
  return true
}

async function publish() {
  if (!doValidate()) return
  publishing.value = true
  try {
    const draft = await api('/api/flows/drafts', {
      method: 'POST',
      body: JSON.stringify({ name: 'studio', graph: toDomainGraph() })
    })
    flowId.value = draft.id
    const pub = await api(`/api/flows/drafts/${draft.id}/publish`, { method: 'POST' })
    versionId.value = pub.id
    uiState.value = 'ok'
    statusText.value = `发布成功：${versionId.value}`
    ElMessage.success(statusText.value)
  } catch (e) {
    uiState.value = 'error'
    issues.value = [{ message: e?.message || '发布失败', level: 'error' }]
    ElMessage.error(e?.message || '发布失败')
  } finally {
    publishing.value = false
  }
}

async function publishAsTool() {
  if (!versionId.value) {
    ElMessage.warning('请先发布版本')
    return
  }
  const name = (toolName.value || '').trim()
  if (!name) {
    ElMessage.warning('请填写 Tool 名称')
    return
  }
  publishingTool.value = true
  try {
    const published = await api('/api/flows/publications/tools', {
      method: 'POST',
      body: JSON.stringify({ versionId: versionId.value, toolName: name })
    })
    statusText.value = `已发布为 Tool：${published}`
    uiState.value = 'ok'
    ElMessage.success(statusText.value)
  } catch (e) {
    ElMessage.error(e?.message || '发布 Tool 失败')
  } finally {
    publishingTool.value = false
  }
}

/**
 * 解析 run.trace；若后端日后提供逐步 API，可在此切换。
 * @param {unknown} data
 * @returns {TraceStep[]}
 */
function parseTrace(data) {
  if (!data || typeof data !== 'object') return []
  const raw = /** @type {any} */ (data).trace
  if (!Array.isArray(raw)) return []
  return raw.map((step) => {
    if (!step || typeof step !== 'object') return { type: 'unknown', input: step, output: null }
    return {
      type: step.type,
      nodeId: step.nodeId || step.id,
      input: step.input,
      output: step.output
    }
  })
}

async function runPlayground() {
  if (!versionId.value) {
    ElMessage.warning('请先发布版本')
    return
  }
  let input
  try {
    input = JSON.parse(playgroundInput.value || '{}')
  } catch {
    ElMessage.error('Playground input JSON 无效')
    return
  }
  running.value = true
  traceSteps.value = []
  runMeta.value = ''
  try {
    const data = await api(`/api/flows/versions/${versionId.value}/run`, {
      method: 'POST',
      body: JSON.stringify(input)
    })
    traceSteps.value = parseTrace(data)
    runMeta.value = `runId=${data?.id || '-'} status=${data?.status || '-'} steps=${traceSteps.value.length}`
    if (!traceSteps.value.length) {
      ElMessage.warning('运行完成但无逐步 trace（可后续接 step API）')
    } else {
      ElMessage.success(`运行完成，共 ${traceSteps.value.length} 步`)
    }
  } catch (e) {
    ElMessage.error(e?.message || '运行失败')
  } finally {
    running.value = false
  }
}

function formatJson(v) {
  try {
    return JSON.stringify(v, null, 2)
  } catch {
    return String(v)
  }
}
</script>

<style scoped>
.studio {
  display: flex;
  flex-direction: column;
  gap: 10px;
  min-height: 70vh;
}
.mb {
  margin-bottom: 4px;
}
.muted {
  color: var(--muted, #94a3b8);
  font-size: 13px;
}
.issues {
  margin: 6px 0 0;
  padding-left: 18px;
}
.nodes {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.chip {
  background: #1e293b;
  border: 0;
  color: #fff;
  border-radius: 10px;
  padding: 6px 10px;
  font-size: 12px;
  cursor: pointer;
}
.chip.ghost {
  background: transparent;
  border: 1px solid #475569;
  color: #cbd5e1;
}
.canvas-wrap {
  height: 420px;
  border: 1px solid #334155;
  border-radius: 12px;
  overflow: hidden;
  background: #0b1220;
}
.row {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  align-items: center;
}
.raw textarea {
  width: 100%;
  background: #0b1220;
  color: var(--text, #e2e8f0);
  border: 1px solid #334155;
  border-radius: 12px;
  padding: 12px;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12px;
}
.config-actions {
  margin-top: 8px;
  display: flex;
  gap: 10px;
  align-items: center;
}
.cfg-err {
  color: #ff8e8e;
  font-size: 12px;
}
.legacy-desc {
  max-width: 560px;
}
.playground .run-meta {
  margin-top: 8px;
}
.trace {
  margin-top: 12px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.trace-step {
  border: 1px solid #334155;
  border-radius: 10px;
  padding: 10px 12px;
  background: #0b1220;
}
.trace-head {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-bottom: 8px;
}
.trace-io {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
}
.io-label {
  font-size: 11px;
  color: #94a3b8;
  margin-bottom: 4px;
  text-transform: uppercase;
  letter-spacing: 0.04em;
}
.trace-io pre {
  margin: 0;
  background: #020617;
  border: 1px solid #1e293b;
  border-radius: 8px;
  padding: 8px;
  overflow: auto;
  max-height: 180px;
  font-size: 12px;
}
:deep(.vue-flow__node) {
  background: #0f172a;
  border: 1px solid #475569;
  color: #e2e8f0;
  border-radius: 10px;
  padding: 8px 12px;
  font-size: 12px;
  white-space: pre-line;
}
@media (max-width: 720px) {
  .trace-io {
    grid-template-columns: 1fr;
  }
}
</style>
