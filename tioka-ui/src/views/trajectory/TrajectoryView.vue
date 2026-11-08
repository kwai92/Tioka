<template>
  <div class="page trajectory" data-testid="trajectory-view">
    <header class="head">
      <div>
        <h1>轨迹</h1>
        <p v-if="trajectory" class="muted">
          Turn {{ trajectory.turn.seq }} · {{ trajectory.turn.status }} ·
          {{ formatTokens(trajectory.turn.promptTokens, trajectory.turn.completionTokens) }}
        </p>
      </div>
      <div class="actions">
        <a
          v-if="timelineLink"
          data-testid="timeline-link"
          class="link"
          :href="timelineLink"
        >
          查看完整时间线
        </a>
        <a
          v-if="trajectory?.traceUrl"
          data-testid="jaeger-link"
          class="link"
          :href="trajectory.traceUrl"
          target="_blank"
          rel="noopener noreferrer"
        >
          在 Jaeger 中查看
        </a>
      </div>
    </header>

    <p v-if="error" class="err">{{ error }}</p>
    <p v-else-if="loading" class="muted">加载中…</p>

    <div v-else class="layout">
      <aside class="turns" data-testid="turn-list">
        <h2>Turn 列表</h2>
        <button
          v-for="t in turns"
          :key="t.turnId"
          type="button"
          class="turn-row"
          :class="{ active: t.turnId === selectedTurnId }"
          @click="selectTurn(t.turnId)"
        >
          <div class="turn-top">
            <span class="seq">#{{ t.seq }}</span>
            <span class="status">{{ t.status }}</span>
          </div>
          <div class="turn-meta">
            {{ t.stepCount }} 步 · {{ formatTokens(t.promptTokens, t.completionTokens) }}
          </div>
          <div class="turn-meta">
            成本 {{ formatCostMicros(t.costMicros) }} ·
            耗时 {{ formatDurationMs(elapsedMs(t.startedAt, t.endedAt)) }}
          </div>
        </button>
        <p v-if="!turns.length" class="muted">暂无 Turn</p>
      </aside>

      <section class="timeline" data-testid="timeline">
        <h2>时间线</h2>
        <VirtualList
          v-if="rows.length"
          :items="rows"
          :item-size="rowHeight"
          :height="560"
        >
          <template #default="{ item: row }">
            <div v-if="row.kind === 'step'" class="row step">
              <strong>第 {{ row.step }} 步</strong>
            </div>
            <div v-else-if="row.kind === 'model'" class="row model" :class="{ failover: row.failover }">
              <span class="tag">模型调用</span>
              <strong>{{ row.modelRef || '—' }}</strong>
              <span class="meta">{{ formatTokens(row.promptTokens, row.completionTokens) }}</span>
              <span class="meta">{{ formatDurationMs(row.latencyMs) }}</span>
              <span v-if="row.failover" class="failover-badge">故障接力</span>
            </div>
            <div
              v-else-if="row.item"
              class="row item"
              :class="{ discarded: row.item.discarded }"
            >
              <div v-if="row.item.discarded" class="discard-badge">已作废（模型切换）</div>
              <div v-if="row.item.type === 'card'" class="approval-meta">
                <span class="tag">审批</span>
                <span v-if="approvalActor(row.item)">审批人 {{ approvalActor(row.item) }}</span>
                <span v-if="approvalTime(row.item)" class="meta">{{ approvalTime(row.item) }}</span>
              </div>
              <a
                v-if="row.item.type === 'child_thread'"
                class="child-link"
                :href="childThreadHref(row.item)"
              >
                子会话 {{ childThreadLabel(row.item) }}
              </a>
              <ItemRenderer
                v-else
                :item="toRuntimeItem(row.item)"
                :pair-result="row.pairResult ? toRuntimeItem(row.pairResult) : undefined"
                :approval="toApproval(row.item)"
              />
            </div>
          </template>
        </VirtualList>
        <p v-else class="muted">选择左侧 Turn 查看轨迹</p>
        <p v-if="truncated" class="muted hint">
          已截断至 {{ maxItems }} 个 item（单 Turn 上限）。
        </p>
      </section>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import ItemRenderer from '@/items/ItemRenderer.vue'
import type { ApprovalDecisionView, RuntimeItemView } from '@/items/types'
import {
  getTrajectory,
  listTurns,
  type Trajectory,
  type TrajectoryItemView,
  type TurnSummary
} from '@/api/trajectory'
import VirtualList from './VirtualList.vue'
import { countItems, flattenTimeline, MAX_TRAJECTORY_ITEMS, type TimelineRow } from './flattenTimeline'
import {
  elapsedMs,
  formatCostMicros,
  formatDurationMs,
  formatTokens,
  timelineHref
} from './format'

const props = defineProps<{
  /** 单测注入 */
  trajectoryOverride?: Trajectory | null
  turnsOverride?: TurnSummary[] | null
  maxItems?: number
}>()

const route = useRoute()
const router = useRouter()

const loading = ref(false)
const error = ref('')
const turns = ref<TurnSummary[]>([])
const trajectory = ref<Trajectory | null>(null)
const rowHeight = 96
const maxItems = computed(() => props.maxItems ?? MAX_TRAJECTORY_ITEMS)

const selectedTurnId = computed(() => {
  const p = route.params.turnId
  return typeof p === 'string' && p ? p : turns.value[0]?.turnId
})

function guessThread(): string {
  return trajectory.value?.turn.threadId ?? 'thd_tools_01'
}

const rows = computed<TimelineRow[]>(() => {
  if (!trajectory.value) return []
  return flattenTimeline(trajectory.value, maxItems.value)
})

const truncated = computed(() => {
  if (!trajectory.value) return false
  return countItems(trajectory.value) > maxItems.value
})

const timelineLink = computed(() => timelineHref(trajectory.value?.turn.requestId))

async function selectTurn(turnId: string) {
  if (turnId === route.params.turnId) {
    await loadTrajectory(turnId)
    return
  }
  await router.replace({
    params: { ...route.params, turnId },
    query: route.query
  })
}

async function loadTurns() {
  if (props.turnsOverride) {
    turns.value = props.turnsOverride
    return
  }
  const tid = typeof route.query.threadId === 'string' ? route.query.threadId : guessThread()
  turns.value = await listTurns(tid)
}

async function loadTrajectory(turnId: string) {
  if (props.trajectoryOverride) {
    trajectory.value = props.trajectoryOverride
    return
  }
  trajectory.value = await getTrajectory(turnId)
}

async function reload() {
  loading.value = true
  error.value = ''
  try {
    await loadTurns()
    const id = selectedTurnId.value
    if (id) await loadTrajectory(id)
    else trajectory.value = null
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
}

watch(
  () => [route.params.turnId, route.query.threadId, props.trajectoryOverride, props.turnsOverride],
  () => {
    void reload()
  },
  { immediate: true }
)

function toRuntimeItem(item: TrajectoryItemView): RuntimeItemView {
  return {
    itemId: item.itemId,
    seq: item.seq,
    step: item.step,
    itemType: item.type,
    role: item.role ?? undefined,
    content: item.content
  }
}

function toApproval(item: TrajectoryItemView): ApprovalDecisionView | null {
  if (item.type !== 'card') return null
  const c = item.content || {}
  const status = typeof c.status === 'string' ? c.status : null
  const decidedBy =
    (typeof c.decidedByName === 'string' && c.decidedByName) ||
    (typeof c.decidedBy === 'string' && c.decidedBy) ||
    undefined
  const approvalId = typeof c.approvalId === 'string' ? c.approvalId : item.itemId
  if (!status && !decidedBy) return null
  return {
    approvalId,
    status: status || 'APPROVED',
    decidedBy,
    toolName: typeof c.toolName === 'string' ? c.toolName : undefined
  }
}

function approvalActor(item: TrajectoryItemView): string {
  const c = item.content || {}
  if (typeof c.decidedByName === 'string' && c.decidedByName) return c.decidedByName
  if (typeof c.decidedBy === 'string') return c.decidedBy
  return ''
}

function approvalTime(item: TrajectoryItemView): string {
  const c = item.content || {}
  return typeof c.decidedAt === 'string' ? c.decidedAt : ''
}

function childThreadHref(item: TrajectoryItemView): string {
  const id = String(item.content?.childThreadId ?? '')
  return id ? `/chat?threadId=${encodeURIComponent(id)}` : '#'
}

function childThreadLabel(item: TrajectoryItemView): string {
  const title = item.content?.title
  const id = item.content?.childThreadId
  if (typeof title === 'string' && title) return title
  return String(id ?? '')
}
</script>

<style scoped>
.trajectory {
  display: grid;
  gap: 16px;
  max-width: 1200px;
}
.head {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  align-items: flex-start;
}
.head h1 {
  margin: 0 0 4px;
}
.muted {
  color: var(--muted, #8aa0b5);
  margin: 0;
}
.err {
  color: #fca5a5;
}
.actions {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
}
.link {
  color: #7dd3fc;
  text-decoration: none;
  font-size: 0.9rem;
}
.link:hover {
  text-decoration: underline;
}
.layout {
  display: grid;
  grid-template-columns: 260px 1fr;
  gap: 16px;
  min-height: 480px;
}
.turns,
.timeline {
  display: grid;
  gap: 8px;
  align-content: start;
}
.turns h2,
.timeline h2 {
  margin: 0;
  font-size: 0.95rem;
  color: var(--muted, #8aa0b5);
  font-weight: 600;
}
.turn-row {
  text-align: left;
  border: 1px solid #243041;
  background: color-mix(in srgb, var(--panel, #111827) 90%, black 10%);
  color: inherit;
  border-radius: 10px;
  padding: 10px 12px;
  cursor: pointer;
  display: grid;
  gap: 4px;
}
.turn-row.active {
  border-color: #38bdf8;
  background: #1e293b;
}
.turn-top {
  display: flex;
  justify-content: space-between;
  gap: 8px;
}
.seq {
  font-weight: 600;
}
.status {
  font-size: 12px;
  color: #86efac;
}
.turn-meta {
  font-size: 12px;
  color: var(--muted, #8aa0b5);
}
.row {
  height: 100%;
  box-sizing: border-box;
  border-radius: 8px;
  padding: 8px 10px;
  display: grid;
  gap: 4px;
  align-content: start;
}
.row.step {
  background: #1e293b;
}
.row.model {
  background: #0f172a;
  border: 1px solid #334155;
  grid-auto-flow: column;
  grid-auto-columns: max-content;
  align-items: center;
  gap: 10px;
}
.row.model.failover {
  border-color: #f59e0b;
}
.failover-badge {
  font-size: 12px;
  color: #fbbf24;
  border: 1px solid #f59e0b;
  border-radius: 999px;
  padding: 2px 8px;
}
.row.item {
  background: transparent;
}
.row.item.discarded {
  opacity: 0.55;
  filter: grayscale(0.4);
}
.discard-badge {
  font-size: 12px;
  color: #cbd5e1;
  background: #334155;
  border-radius: 6px;
  padding: 2px 8px;
  width: fit-content;
}
.approval-meta {
  display: flex;
  gap: 10px;
  align-items: center;
  font-size: 12px;
  color: #e2e8f0;
  margin-bottom: 4px;
}
.tag {
  font-size: 11px;
  color: #7dd3fc;
  text-transform: uppercase;
}
.meta {
  font-size: 12px;
  color: var(--muted, #8aa0b5);
}
.child-link {
  color: #7dd3fc;
  font-size: 0.9rem;
}
.hint {
  font-size: 12px;
}
@media (max-width: 900px) {
  .layout {
    grid-template-columns: 1fr;
  }
}
</style>
