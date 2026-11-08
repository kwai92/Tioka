<template>
  <div class="page" data-testid="rules-view">
    <header class="head">
      <div>
        <h1>编排规则</h1>
        <p class="muted">规则列表 · 版本草稿 · 试运行 · 发布灰度/稳定 · 观察模式审阅</p>
      </div>
      <el-button type="primary" :loading="creating" @click="onCreateRule">新建规则</el-button>
    </header>

    <el-alert v-if="error" :title="error" type="error" show-icon class="mb" @close="error = ''" />

    <el-empty v-if="!loading && !rules.length" description="暂无规则" />

    <el-table v-else :data="rules" row-key="id" class="mb">
      <el-table-column prop="name" label="名称" min-width="160" />
      <el-table-column label="稳定" width="120">
        <template #default="{ row }">
          <el-tag v-if="row.stableVersionId" size="small" type="success">稳定</el-tag>
          <span v-else class="muted">—</span>
        </template>
      </el-table-column>
      <el-table-column label="灰度" width="120">
        <template #default="{ row }">
          <el-tag v-if="row.canaryVersionId" size="small" type="warning">灰度</el-tag>
          <span v-else class="muted">—</span>
        </template>
      </el-table-column>
      <el-table-column label="版本数" width="90">
        <template #default="{ row }">{{ row.versions?.length ?? 0 }}</template>
      </el-table-column>
      <el-table-column label="操作" min-width="320" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openDraft(row.id)">新草稿</el-button>
          <el-button
            v-if="latestDraft(row)"
            link
            type="primary"
            @click="openPublish(row.id, latestDraft(row)!.id)"
          >
            发布
          </el-button>
          <el-button
            v-if="row.versions?.length"
            link
            @click="openDryRun(row.id, pickVersionId(row))"
          >
            试运行
          </el-button>
          <el-button link @click="openObserve(row.id)">观察审阅</el-button>
        </template>
      </el-table-column>
    </el-table>

    <section v-if="selectedRule" class="versions">
      <h2>{{ selectedRule.name }} · 版本</h2>
      <el-table :data="selectedRule.versions || []" row-key="id" size="small">
        <el-table-column prop="version" label="#" width="60" />
        <el-table-column prop="stage" label="阶段" width="100">
          <template #default="{ row }">
            <el-tag size="small">{{ row.stage }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="eventType" label="事件类型" min-width="180" show-overflow-tooltip />
        <el-table-column prop="acceptMode" label="接单" width="90" />
        <el-table-column prop="conditionCel" label="CEL" min-width="200" show-overflow-tooltip />
        <el-table-column label="操作" width="200">
          <template #default="{ row }">
            <el-button
              v-if="row.stage === 'DRAFT' || row.stage === 'CANARY'"
              link
              type="primary"
              @click="openPublish(selectedRule!.id, row.id)"
            >
              发布
            </el-button>
            <el-button link @click="openDryRun(selectedRule!.id, row.id)">试运行</el-button>
          </template>
        </el-table-column>
      </el-table>
    </section>

    <!-- 版本草稿 -->
    <el-dialog v-model="draftOpen" title="版本草稿" width="720px" destroy-on-close>
      <el-form label-width="120px" @submit.prevent>
        <el-form-item label="事件类型" required>
          <el-input v-model="draft.eventType" placeholder="tioka.connector.im_inbound.v1" />
        </el-form-item>
        <el-form-item label="CEL 条件" required>
          <CelEditor v-model="draft.conditionCel" :cel-error="celError" />
        </el-form-item>
        <el-form-item label="动作类型">
          <el-select v-model="draft.actionType" style="width: 160px">
            <el-option label="agent" value="agent" />
            <el-option label="admission" value="admission" />
          </el-select>
        </el-form-item>
        <el-form-item label="Agent 版本 ID">
          <el-input v-model="draft.agentVersionId" placeholder="av_..." />
        </el-form-item>
        <el-form-item label="并发">
          <el-select v-model="draft.concurrency" style="width: 140px">
            <el-option label="serial" value="serial" />
            <el-option label="merge" value="merge" />
            <el-option label="parallel" value="parallel" />
          </el-select>
        </el-form-item>
        <el-form-item label="接单模式">
          <el-select v-model="draft.acceptMode" style="width: 140px">
            <el-option label="observe" value="observe" />
            <el-option label="auto" value="auto" />
            <el-option label="off" value="off" />
          </el-select>
        </el-form-item>
        <el-form-item label="告知策略">
          <el-select v-model="draft.tell" style="width: 140px">
            <el-option label="needed" value="needed" />
            <el-option label="always" value="always" />
            <el-option label="never" value="never" />
          </el-select>
        </el-form-item>
        <el-form-item label="派生事件白名单">
          <el-input
            v-model="draft.emitsText"
            type="textarea"
            :rows="2"
            placeholder="每行一个事件类型"
          />
        </el-form-item>
        <el-form-item label="服务账号" required>
          <el-input v-model="draft.serviceAccountId" placeholder="sa_orch" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="draftOpen = false">取消</el-button>
        <el-button type="primary" :loading="draftBusy" @click="submitDraft">保存草稿</el-button>
      </template>
    </el-dialog>

    <!-- 发布 -->
    <el-dialog v-model="publishOpen" title="发布版本" width="420px" destroy-on-close>
      <el-form label-width="100px">
        <el-form-item label="目标阶段">
          <el-radio-group v-model="publishForm.stage">
            <el-radio value="CANARY">灰度</el-radio>
            <el-radio value="STABLE">稳定</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="publishForm.stage === 'CANARY'" label="灰度比例">
          <el-input-number v-model="publishForm.canaryPercent" :min="0" :max="100" />
        </el-form-item>
        <el-form-item label="发布说明">
          <el-input v-model="publishForm.publishNote" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="publishOpen = false">取消</el-button>
        <el-button type="primary" :loading="publishBusy" @click="submitPublish">发布</el-button>
      </template>
    </el-dialog>

    <!-- 试运行 -->
    <el-drawer v-model="dryRunOpen" title="试运行" size="480px" destroy-on-close>
      <el-form label-position="top">
        <el-form-item label="事件 JSON">
          <el-input v-model="dryRunEventText" type="textarea" :rows="10" />
        </el-form-item>
        <el-button type="primary" :loading="dryRunBusy" @click="runDryRun">执行试运行</el-button>
      </el-form>
      <div
        v-if="dryRunResult"
        class="dry-run-result"
        data-testid="dry-run-result"
      >
        <p><strong>matched</strong>：{{ dryRunResult.matched }}</p>
        <p><strong>decision</strong>：{{ dryRunResult.decision }}</p>
        <p><strong>reason</strong>：{{ dryRunResult.reason }}</p>
        <p v-if="dryRunResult.fellBackToStable"><strong>fellBackToStable</strong>：true</p>
      </div>
    </el-drawer>

    <!-- 观察模式审阅 -->
    <el-drawer v-model="observeOpen" title="观察模式审阅（WOULD_ACCEPT）" size="560px" destroy-on-close>
      <div data-testid="observe-review">
        <el-empty v-if="!decisions.length" description="暂无 WOULD_ACCEPT 决定" />
        <el-table v-else :data="decisions" row-key="id" size="small">
          <el-table-column prop="createAt" label="时间" width="170" />
          <el-table-column prop="subjectKey" label="主体" min-width="140" show-overflow-tooltip />
          <el-table-column prop="strength" label="强度" width="100" />
          <el-table-column prop="reason" label="原因" min-width="120" show-overflow-tooltip />
        </el-table>
      </div>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import {
  createRule,
  createVersion,
  dryRunVersion,
  listDecisions,
  listRules,
  publishVersion,
  type DryRunResult,
  type OrchDecision,
  type OrchRule,
  type RuleAcceptMode,
  type RuleConcurrency,
  type RuleTell
} from '@/api/orch'
import CelEditor from './CelEditor.vue'
import { parseCelError, type CelErrorLocation } from './parseCelError'

const rules = ref<OrchRule[]>([])
const loading = ref(false)
const creating = ref(false)
const error = ref('')
const selectedRuleId = ref<string | null>(null)

const draftOpen = ref(false)
const draftBusy = ref(false)
const celError = ref<CelErrorLocation | null>(null)
const draft = reactive({
  ruleId: '',
  eventType: 'tioka.connector.im_inbound.v1',
  conditionCel: 'event.source == "feishu"',
  actionType: 'agent' as 'agent' | 'admission',
  agentVersionId: 'av_demo_01',
  concurrency: 'serial' as RuleConcurrency,
  acceptMode: 'observe' as RuleAcceptMode,
  tell: 'needed' as RuleTell,
  emitsText: '',
  serviceAccountId: 'sa_orch'
})

const publishOpen = ref(false)
const publishBusy = ref(false)
const publishTarget = reactive({ ruleId: '', versionId: '' })
const publishForm = reactive({
  stage: 'CANARY' as 'CANARY' | 'STABLE',
  canaryPercent: 10,
  publishNote: ''
})

const dryRunOpen = ref(false)
const dryRunBusy = ref(false)
const dryRunTarget = reactive({ ruleId: '', versionId: '' })
const dryRunEventText = ref(
  JSON.stringify(
    {
      source: 'feishu',
      type: 'tioka.connector.im_inbound.v1',
      subjectKey: 'feishu:chat_demo',
      hops: 0,
      attrs: { mentioned: 'true' }
    },
    null,
    2
  )
)
const dryRunResult = ref<DryRunResult | null>(null)

const observeOpen = ref(false)
const decisions = ref<OrchDecision[]>([])

const selectedRule = computed(
  () => rules.value.find((r) => r.id === selectedRuleId.value) ?? null
)

function latestDraft(row: OrchRule) {
  const drafts = (row.versions || []).filter((v) => v.stage === 'DRAFT')
  return drafts.sort((a, b) => b.version - a.version)[0] ?? null
}

function pickVersionId(row: OrchRule): string {
  return (
    row.canaryVersionId ||
    row.stableVersionId ||
    row.versions?.[0]?.id ||
    ''
  )
}

async function reload() {
  loading.value = true
  error.value = ''
  try {
    rules.value = await listRules()
    if (selectedRuleId.value && !rules.value.some((r) => r.id === selectedRuleId.value)) {
      selectedRuleId.value = null
    }
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
}

async function onCreateRule() {
  creating.value = true
  error.value = ''
  try {
    const name = `新规则 ${new Date().toISOString().slice(0, 16)}`
    const rule = await createRule(name)
    await reload()
    selectedRuleId.value = rule.id
    openDraft(rule.id)
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    creating.value = false
  }
}

function openDraft(ruleId: string) {
  selectedRuleId.value = ruleId
  draft.ruleId = ruleId
  draft.eventType = 'tioka.connector.im_inbound.v1'
  draft.conditionCel = 'event.source == "feishu"'
  draft.actionType = 'agent'
  draft.agentVersionId = 'av_demo_01'
  draft.concurrency = 'serial'
  draft.acceptMode = 'observe'
  draft.tell = 'needed'
  draft.emitsText = ''
  draft.serviceAccountId = 'sa_orch'
  celError.value = null
  draftOpen.value = true
}

async function submitDraft() {
  draftBusy.value = true
  celError.value = null
  error.value = ''
  try {
    const emits = draft.emitsText
      .split(/\n|,/)
      .map((s) => s.trim())
      .filter(Boolean)
    await createVersion(draft.ruleId, {
      eventType: draft.eventType,
      conditionCel: draft.conditionCel,
      actions: [
        {
          type: draft.actionType,
          agentVersionId: draft.agentVersionId || undefined,
          maxSteps: 30,
          maxDuration: '2h'
        }
      ],
      concurrency: draft.concurrency,
      acceptMode: draft.acceptMode,
      tell: draft.tell,
      emits,
      serviceAccountId: draft.serviceAccountId
    })
    draftOpen.value = false
    await reload()
  } catch (e: unknown) {
    const msg = e instanceof Error ? e.message : String(e)
    celError.value = parseCelError(msg)
    if (!celError.value) error.value = msg
  } finally {
    draftBusy.value = false
  }
}

function openPublish(ruleId: string, versionId: string) {
  publishTarget.ruleId = ruleId
  publishTarget.versionId = versionId
  publishForm.stage = 'CANARY'
  publishForm.canaryPercent = 10
  publishForm.publishNote = ''
  publishOpen.value = true
}

async function submitPublish() {
  publishBusy.value = true
  error.value = ''
  try {
    await publishVersion(publishTarget.ruleId, publishTarget.versionId, {
      stage: publishForm.stage,
      canaryPercent: publishForm.stage === 'CANARY' ? publishForm.canaryPercent : null,
      publishNote: publishForm.publishNote || null
    })
    publishOpen.value = false
    await reload()
  } catch (e: unknown) {
    const msg = e instanceof Error ? e.message : String(e)
    error.value = msg
    const loc = parseCelError(msg)
    if (loc) celError.value = loc
  } finally {
    publishBusy.value = false
  }
}

function openDryRun(ruleId: string, versionId: string) {
  dryRunTarget.ruleId = ruleId
  dryRunTarget.versionId = versionId
  dryRunResult.value = null
  dryRunOpen.value = true
}

async function runDryRun() {
  dryRunBusy.value = true
  error.value = ''
  try {
    let event: Record<string, unknown>
    try {
      event = JSON.parse(dryRunEventText.value) as Record<string, unknown>
    } catch {
      throw new Error('事件 JSON 无法解析')
    }
    dryRunResult.value = await dryRunVersion(
      dryRunTarget.ruleId,
      dryRunTarget.versionId,
      event
    )
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    dryRunBusy.value = false
  }
}

async function openObserve(ruleId: string) {
  selectedRuleId.value = ruleId
  observeOpen.value = true
  decisions.value = []
  try {
    decisions.value = await listDecisions({
      ruleId,
      decision: 'WOULD_ACCEPT'
    })
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : String(e)
  }
}

onMounted(() => {
  void reload()
})

defineExpose({
  rules,
  selectedRuleId,
  openDraft,
  submitDraft,
  celError,
  openDryRun,
  runDryRun,
  dryRunResult,
  openObserve,
  decisions,
  openPublish,
  submitPublish,
  reload
})
</script>

<style scoped>
.page { display: grid; gap: 12px; max-width: 1100px; }
.head { display: flex; justify-content: space-between; align-items: flex-start; gap: 12px; }
h1, h2 { margin: 0; }
.muted { color: var(--muted, #8aa0b5); margin: 4px 0 0; font-size: 13px; }
.mb { margin-bottom: 4px; }
.versions { display: grid; gap: 8px; }
.dry-run-result {
  margin-top: 16px;
  padding: 12px;
  border-radius: 6px;
  background: var(--panel, #0f1720);
  border: 1px solid var(--border, #2a3a4d);
  display: grid;
  gap: 4px;
}
.dry-run-result p { margin: 0; font-size: 13px; }
</style>
