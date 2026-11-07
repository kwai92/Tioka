<template>
  <div class="page">
    <h1>Agent 配置</h1>
    <p class="muted">分区编辑草稿 → 校验 / 发布 → 版本 diff / 回滚</p>

    <el-alert v-if="msg" :title="msg" type="success" show-icon class="mb" @close="msg = ''" />
    <el-alert v-if="error" :title="error" type="error" show-icon class="mb" @close="error = ''" />
    <el-alert
      v-if="publishErrors.length"
      type="error"
      show-icon
      class="mb"
      :closable="false"
      title="发布校验失败"
    >
      <ul class="err-list">
        <li v-for="(e, i) in publishErrors" :key="i">
          <a href="#" @click.prevent="jumpToError(e.path)">{{ e.path }}</a>
          — {{ e.code }}: {{ e.message }}
        </li>
      </ul>
    </el-alert>

    <el-card shadow="never" class="mb">
      <template #header>创建 Agent</template>
      <el-form :inline="true" @submit.prevent>
        <el-form-item label="名称">
          <el-input v-model="createName" placeholder="Agent 名称" />
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
        <el-table-column label="操作" width="160">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="pick(row)">编辑</el-button>
            <el-button link type="primary" size="small" @click="openShare(row.id)">分享</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card v-if="agentId && spec" shadow="never" class="mb">
      <template #header>
        <div class="card-head">
          <span>草稿 · {{ agentId }} · rev {{ revision }}</span>
          <div class="actions">
            <el-button :loading="busy" @click="doSave">保存草稿</el-button>
            <el-button :loading="busy" @click="doValidate">校验</el-button>
            <el-input v-model="publishNote" placeholder="发布备注" style="width: 160px" />
            <el-button type="primary" :loading="busy" @click="doPublish">发布</el-button>
          </div>
        </div>
      </template>

      <el-tabs v-model="activeTab" type="border-card">
        <el-tab-pane
          v-for="sec in EDITOR_SECTIONS"
          :key="sec"
          :name="sec"
          :label="sectionLabel(sec)"
        >
          <div :ref="(el) => setSectionEl(sec, el)" :class="{ 'sec-error': errorSections.has(sec) }">
            <!-- Profile -->
            <el-form v-if="sec === 'profile'" label-width="120px" data-path="/profile">
              <el-form-item label="名称" data-path="/profile/name">
                <el-input v-model="spec.profile.name" />
              </el-form-item>
              <el-form-item label="描述">
                <el-input v-model="spec.profile.description" type="textarea" :rows="2" />
              </el-form-item>
              <el-form-item label="图标">
                <el-input v-model="spec.profile.icon" placeholder="可选" />
              </el-form-item>
              <el-form-item label="标签">
                <el-select
                  v-model="spec.profile.tags"
                  multiple
                  filterable
                  allow-create
                  default-first-option
                  style="width: 100%"
                />
              </el-form-item>
              <el-form-item label="类型">
                <el-select v-model="spec.profile.kind" style="width: 200px">
                  <el-option label="CHAT" value="CHAT" />
                  <el-option label="TASK" value="TASK" />
                  <el-option label="READONLY" value="READONLY" />
                </el-select>
              </el-form-item>
            </el-form>

            <!-- Instructions -->
            <div v-else-if="sec === 'instructions'" data-path="/instructions">
              <div
                v-for="(s, i) in spec.instructions.sections"
                :key="i"
                class="row"
                :data-path="`/instructions/sections/${i}`"
              >
                <el-input v-model="s.key" placeholder="key" style="width: 140px" />
                <el-input v-model="s.text" type="textarea" :rows="2" placeholder="text" />
                <el-button link type="danger" @click="spec.instructions.sections.splice(i, 1)">删</el-button>
              </div>
              <el-button size="small" @click="spec.instructions.sections.push({ key: 'sys', text: '' })">
                加一节
              </el-button>
            </div>

            <!-- Model -->
            <el-form v-else-if="sec === 'model'" label-width="140px" data-path="/model">
              <el-form-item label="RoutingPlan" data-path="/model/routingPlanId">
                <el-select v-model="spec.model.routingPlanId" filterable style="width: 100%">
                  <el-option
                    v-for="p in plans"
                    :key="p.id"
                    :label="`${p.name} (${p.type})`"
                    :value="p.id"
                  />
                </el-select>
              </el-form-item>
              <el-form-item label="允许用户覆盖">
                <el-switch v-model="spec.model.allowUserOverride" />
              </el-form-item>
            </el-form>

            <!-- Tools -->
            <div v-else-if="sec === 'tools'" data-path="/tools">
              <el-form-item label="内置工具">
                <el-select
                  v-model="spec.tools.builtin"
                  multiple
                  filterable
                  allow-create
                  default-first-option
                  style="width: 100%"
                  placeholder="输入工具名回车"
                />
              </el-form-item>
              <p class="muted">MCP 绑定</p>
              <div
                v-for="(m, i) in spec.tools.mcp"
                :key="i"
                class="mcp-row"
                :data-path="`/tools/mcp/${i}`"
              >
                <el-select
                  v-model="m.serverId"
                  filterable
                  placeholder="MCP Server"
                  style="width: 240px"
                  @change="loadToolsFor(m.serverId)"
                >
                  <el-option v-for="s in mcpServers" :key="s.id" :label="s.name || s.id" :value="s.id" />
                </el-select>
                <el-select
                  v-model="m.allowlist"
                  multiple
                  filterable
                  allow-create
                  style="flex: 1"
                  placeholder="allowlist"
                >
                  <el-option
                    v-for="t in toolsByServer[m.serverId] || []"
                    :key="t"
                    :label="t"
                    :value="t"
                  />
                </el-select>
                <el-button link type="danger" @click="spec.tools.mcp.splice(i, 1)">删</el-button>
              </div>
              <el-button size="small" @click="spec.tools.mcp.push({ serverId: '', allowlist: [] })">
                加 MCP
              </el-button>
              <p class="muted tip">flows / business：M3，固定空数组</p>
            </div>

            <!-- Skills -->
            <div v-else-if="sec === 'skills'" data-path="/skills">
              <div
                v-for="(sk, i) in spec.skills"
                :key="i"
                class="row"
                :data-path="`/skills/${i}`"
              >
                <el-select v-model="sk.skillVersionId" filterable style="flex: 1" placeholder="已发布 Skill">
                  <el-option
                    v-for="s in publishedSkills"
                    :key="s.id"
                    :label="s.name"
                    :value="s.id"
                  />
                </el-select>
                <el-select v-model="sk.mode" style="width: 120px">
                  <el-option label="ALWAYS" value="ALWAYS" />
                  <el-option label="MANUAL" value="MANUAL" />
                </el-select>
                <el-button link type="danger" @click="spec.skills.splice(i, 1)">删</el-button>
              </div>
              <el-button
                size="small"
                @click="spec.skills.push({ skillVersionId: '', mode: 'ALWAYS' })"
              >
                加 Skill
              </el-button>
            </div>

            <!-- Knowledge -->
            <el-form v-else-if="sec === 'knowledge'" label-width="120px" data-path="/knowledge">
              <el-form-item label="知识库 ID">
                <el-select
                  v-model="spec.knowledge.kbIds"
                  multiple
                  filterable
                  allow-create
                  default-first-option
                  style="width: 100%"
                  placeholder="输入 kbId 回车（暂无列表 API）"
                />
              </el-form-item>
              <el-form-item label="模式">
                <el-select v-model="spec.knowledge.mode" style="width: 200px">
                  <el-option label="TOOL" value="TOOL" />
                  <el-option label="AUTO_RETRIEVE" value="AUTO_RETRIEVE" />
                </el-select>
              </el-form-item>
            </el-form>

            <!-- Sandbox -->
            <el-form v-else-if="sec === 'sandbox'" label-width="140px" data-path="/sandbox">
              <el-form-item label="级别">
                <el-select v-model="spec.sandbox.level" style="width: 160px">
                  <el-option label="READ" value="READ" />
                  <el-option label="EDIT" value="EDIT" />
                  <el-option label="FULL" value="FULL" />
                </el-select>
              </el-form-item>
              <el-form-item label="网络白名单">
                <el-select
                  v-model="spec.sandbox.networkAllowlist"
                  multiple
                  filterable
                  allow-create
                  default-first-option
                  style="width: 100%"
                />
              </el-form-item>
            </el-form>

            <!-- Approval -->
            <div v-else-if="sec === 'approval'" data-path="/approval">
              <el-form-item label="审批人">
                <el-input v-model="spec.approval.assignee" placeholder="可空 = 发起人" />
              </el-form-item>
              <p class="muted">toolPolicies（工具名 → auto/approve/deny）</p>
              <div v-for="(pol, i) in policyRows" :key="i" class="row">
                <el-input v-model="pol.tool" placeholder="toolName" style="width: 200px" />
                <el-select v-model="pol.policy" style="width: 140px">
                  <el-option label="auto" value="auto" />
                  <el-option label="approve" value="approve" />
                  <el-option label="deny" value="deny" />
                </el-select>
                <el-button link type="danger" @click="removePolicy(i)">删</el-button>
              </div>
              <el-button size="small" @click="policyRows.push({ tool: '', policy: 'approve' })">
                加策略
              </el-button>
            </div>

            <!-- Limits -->
            <el-form v-else-if="sec === 'limits'" label-width="160px" data-path="/limits">
              <el-form-item label="maxSteps">
                <el-input-number v-model="spec.limits.maxSteps" :min="1" />
              </el-form-item>
              <el-form-item label="maxTokensPerTurn">
                <el-input-number v-model="spec.limits.maxTokensPerTurn" :min="1" :controls="true" />
                <el-button link class="ml" @click="spec.limits.maxTokensPerTurn = null">清空</el-button>
              </el-form-item>
            </el-form>
          </div>
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <el-card v-if="agentId" shadow="never">
      <template #header>
        <div class="card-head">
          <span>版本</span>
          <el-button size="small" :disabled="!diffFrom || !diffTo" @click="loadDiff">看 diff</el-button>
        </div>
      </template>
      <el-table :data="versions" size="small" stripe>
        <el-table-column label="from" width="70">
          <template #default="{ row }">
            <el-radio v-model="diffFrom" :value="row.versionId">&nbsp;</el-radio>
          </template>
        </el-table-column>
        <el-table-column label="to" width="70">
          <template #default="{ row }">
            <el-radio v-model="diffTo" :value="row.versionId">&nbsp;</el-radio>
          </template>
        </el-table-column>
        <el-table-column prop="versionNo" label="#" width="60" />
        <el-table-column prop="status" label="状态" width="120" />
        <el-table-column prop="publishedAt" label="发布时间" min-width="160" />
        <el-table-column prop="note" label="备注" min-width="120" />
        <el-table-column label="当前" width="70">
          <template #default="{ row }">{{ row.current ? '✓' : '' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="100">
          <template #default="{ row }">
            <el-button
              v-if="!row.current && row.status === 'PUBLISHED'"
              link
              type="warning"
              size="small"
              @click="doRollback(row.versionId)"
            >
              回滚
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      <pre v-if="diffOps" class="diff">{{ JSON.stringify(diffOps, null, 2) }}</pre>
    </el-card>

    <ResourceAclDialog
      v-model="shareOpen"
      resource-type="agent"
      :resource-id="shareAgentId"
    />
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { ElMessageBox } from 'element-plus'
import { api } from '../api'
import ResourceAclDialog from '@/components/ResourceAclDialog.vue'
import {
  EDITOR_SECTIONS,
  diffVersions,
  extractPublishErrors,
  firstErrorPath,
  getDraft,
  listVersions,
  mapPublishErrorsToSections,
  publishAgent,
  rollbackAgent,
  saveDraft,
  sectionForPath,
  validateAgent
} from './agentEditor'

const createName = ref('PoC Agent')
const agentId = ref('')
const agents = ref([])
const plans = ref([])
const mcpServers = ref([])
const skills = ref([])
const toolsByServer = reactive({})
const msg = ref('')
const error = ref('')
const busy = ref(false)
const shareOpen = ref(false)
const shareAgentId = ref('')

function openShare(id) {
  shareAgentId.value = id
  shareOpen.value = true
}
const revision = ref(0)
const spec = ref(null)
const activeTab = ref('profile')
const publishNote = ref('')
const publishErrors = ref([])
const errorSections = ref(new Set())
const versions = ref([])
const diffFrom = ref('')
const diffTo = ref('')
const diffOps = ref(null)
const sectionEls = reactive({})
const policyRows = ref([])

const publishedSkills = computed(() => skills.value.filter((s) => s.published))

const SECTION_LABELS = {
  profile: 'Profile',
  instructions: 'Instructions',
  model: 'Model',
  tools: 'Tools',
  skills: 'Skills',
  knowledge: 'Knowledge',
  sandbox: 'Sandbox',
  approval: 'Approval',
  limits: 'Limits'
}

function sectionLabel(sec) {
  const base = SECTION_LABELS[sec] || sec
  return errorSections.value.has(sec) ? `${base} !` : base
}

function setSectionEl(sec, el) {
  if (el) sectionEls[sec] = el
}

function syncPoliciesFromSpec() {
  const tp = spec.value?.approval?.toolPolicies || {}
  policyRows.value = Object.entries(tp).map(([tool, policy]) => ({ tool, policy }))
}

function syncPoliciesToSpec() {
  if (!spec.value) return
  const map = {}
  for (const r of policyRows.value) {
    if (r.tool) map[r.tool] = r.policy || 'approve'
  }
  spec.value.approval.toolPolicies = map
}

function removePolicy(i) {
  policyRows.value.splice(i, 1)
}

async function refreshList() {
  agents.value = await api('/api/agents')
  plans.value = (await api('/api/model/routing-plans')).filter((p) => p.published)
  mcpServers.value = await api('/api/mcp/servers')
  skills.value = await api('/api/skills')
}

async function loadToolsFor(serverId) {
  if (!serverId || toolsByServer[serverId]) return
  try {
    const tools = await api(`/api/mcp/servers/${encodeURIComponent(serverId)}/tools`)
    toolsByServer[serverId] = (tools || []).map((t) => t.name || t)
  } catch {
    toolsByServer[serverId] = []
  }
}

async function create() {
  const a = await api('/api/agents', {
    method: 'POST',
    body: JSON.stringify({ name: createName.value, workspaceId: 'ws_default' })
  })
  await refreshList()
  await pick(a)
}

function onPick(row) {
  if (row) pick(row)
}

async function pick(a) {
  agentId.value = a.id
  publishErrors.value = []
  errorSections.value = new Set()
  diffOps.value = null
  await loadEditor()
}

async function loadEditor() {
  error.value = ''
  try {
    const draft = await getDraft(api, agentId.value)
    spec.value = draft.spec
    revision.value = draft.revision
    syncPoliciesFromSpec()
    for (const m of spec.value.tools?.mcp || []) {
      if (m.serverId) await loadToolsFor(m.serverId)
    }
    versions.value = await listVersions(api, agentId.value)
  } catch (e) {
    error.value = e.message
    spec.value = null
  }
}

function applyPublishErrors(errs) {
  publishErrors.value = errs
  errorSections.value = new Set(mapPublishErrorsToSections(errs))
  const path = firstErrorPath(errs)
  if (path) jumpToError(path)
}

async function jumpToError(path) {
  const sec = sectionForPath(path)
  if (sec) activeTab.value = sec
  await nextTick()
  const el =
    document.querySelector(`[data-path="${CSS.escape(path)}"]`) ||
    (sec && sectionEls[sec])
  if (el && typeof el.scrollIntoView === 'function') {
    el.scrollIntoView({ behavior: 'smooth', block: 'center' })
  }
}

async function doSave() {
  if (!spec.value) return
  msg.value = ''
  error.value = ''
  busy.value = true
  syncPoliciesToSpec()
  try {
    const out = await saveDraft(api, agentId.value, spec.value, revision.value)
    spec.value = out.spec
    revision.value = out.revision
    syncPoliciesFromSpec()
    msg.value = `草稿已保存 rev=${out.revision}`
  } catch (e) {
    if (e.status === 409 || e.code === 40901) {
      error.value = '他人已保存，请重新加载草稿'
      await loadEditor()
    } else {
      error.value = e.message
    }
  } finally {
    busy.value = false
  }
}

async function doValidate() {
  error.value = ''
  publishErrors.value = []
  busy.value = true
  try {
    await doSave()
    const r = await validateAgent(api, agentId.value)
    if (r.ok) {
      msg.value = '校验通过'
    } else {
      applyPublishErrors(r.errors || [])
      error.value = '校验未通过'
    }
  } catch (e) {
    const errs = extractPublishErrors(e)
    if (errs.length) applyPublishErrors(errs)
    else error.value = e.message
  } finally {
    busy.value = false
  }
}

async function doPublish() {
  error.value = ''
  publishErrors.value = []
  busy.value = true
  syncPoliciesToSpec()
  try {
    const saved = await saveDraft(api, agentId.value, spec.value, revision.value)
    revision.value = saved.revision
    spec.value = saved.spec
    const pub = await publishAgent(api, agentId.value, revision.value, publishNote.value)
    msg.value = `已发布 v${pub.versionNo} (${pub.versionId})`
    await refreshList()
    await loadEditor()
  } catch (e) {
    const errs = extractPublishErrors(e)
    if (errs.length) {
      applyPublishErrors(errs)
      error.value = e.message || '发布校验失败'
    } else if (e.status === 409 || e.code === 40901) {
      error.value = '他人已保存，请重新加载草稿'
      await loadEditor()
    } else {
      error.value = e.message
    }
  } finally {
    busy.value = false
  }
}

async function loadDiff() {
  if (!diffFrom.value || !diffTo.value) return
  try {
    diffOps.value = await diffVersions(api, agentId.value, diffFrom.value, diffTo.value)
  } catch (e) {
    error.value = e.message
  }
}

async function doRollback(versionId) {
  try {
    await ElMessageBox.confirm(`确认回滚到版本 ${versionId}？将重跑发布校验。`, '回滚确认', {
      type: 'warning'
    })
  } catch {
    return
  }
  busy.value = true
  error.value = ''
  publishErrors.value = []
  try {
    const r = await rollbackAgent(api, agentId.value, versionId)
    msg.value = `已回滚到 v${r.versionNo}`
    await refreshList()
    await loadEditor()
  } catch (e) {
    const errs = extractPublishErrors(e)
    if (errs.length) applyPublishErrors(errs)
    error.value = e.message
  } finally {
    busy.value = false
  }
}

watch(agentId, () => {
  diffFrom.value = ''
  diffTo.value = ''
})

onMounted(refreshList)
</script>

<style scoped>
.page { max-width: 1100px; display: grid; gap: 8px; }
.muted { color: var(--muted); margin: 0 0 8px; }
.tip { margin-top: 8px; }
.mb { margin-bottom: 12px; }
.ml { margin-left: 8px; }
.card-head { display: flex; justify-content: space-between; align-items: center; gap: 8px; flex-wrap: wrap; }
.actions { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
.row, .mcp-row { display: flex; gap: 8px; margin-bottom: 8px; align-items: flex-start; }
.sec-error { outline: 1px solid var(--el-color-danger); border-radius: 4px; padding: 8px; }
.err-list { margin: 4px 0 0; padding-left: 18px; }
.diff { background: var(--el-fill-color-light); padding: 12px; overflow: auto; max-height: 320px; font-size: 12px; }
</style>
