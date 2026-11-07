/**
 * FE-15 / FE-16 编排 API（手写类型；OpenAPI 合入后换 schema.d.ts）。
 * 路径对齐 M2-02 规则与工单 REST。
 * PK-09 已合入：规则 CRUD / 试运行走真接口；失败时回退 fixtures。
 * 观察模式审阅（GET /decisions）ORC-03 未覆盖处仍用 fixtures。
 */
import { api } from './http'
import rulesFixture from '@/__fixtures__/orch-rules-response.json'
import workItemsFixture from '@/__fixtures__/orch-work-items-response.json'
import decisionsFixture from '@/views/orch/rules/__fixtures__/decisions-would-accept.json'

export type RuleStage = 'DRAFT' | 'CANARY' | 'STABLE' | 'RETIRED'

export type RuleAcceptMode = 'off' | 'observe' | 'auto'

export type RuleConcurrency = 'serial' | 'merge' | 'parallel'

export type RuleTell = 'always' | 'needed' | 'never'

export type OrchRuleAction = {
  type: 'agent' | 'flow' | 'admission'
  agentVersionId?: string
  flowPublicationId?: string
  maxSteps?: number
  maxDuration?: string
  prompt?: string
  inputMapping?: Record<string, string>
}

export type OrchRuleVersion = {
  id: string
  ruleId: string
  version: number
  eventType: string
  conditionCel: string
  actions: OrchRuleAction[]
  concurrency: RuleConcurrency
  acceptMode: RuleAcceptMode
  tell: RuleTell
  emits: string[]
  serviceAccountId: string
  stage: RuleStage
  canaryPercent?: number | null
  schedule?: string | null
  contentHash?: string | null
  publishNote?: string | null
  previousEffectiveVersionId?: string | null
  publishedBy?: string | null
  publishedAt?: string | null
}

export type OrchRule = {
  id: string
  name: string
  workspaceId?: string | null
  ownerId?: string | null
  pausedAt?: string | null
  pausedReason?: string | null
  stableVersionId?: string | null
  canaryVersionId?: string | null
  versions?: OrchRuleVersion[]
}

export type CreateVersionBody = {
  eventType: string
  conditionCel: string
  actions: OrchRuleAction[]
  concurrency: RuleConcurrency
  acceptMode: RuleAcceptMode
  tell: RuleTell
  emits: string[]
  serviceAccountId: string
  schedule?: Record<string, unknown> | null
  escalate?: Record<string, unknown> | null
}

export type PublishBody = {
  stage: 'CANARY' | 'STABLE'
  canaryPercent?: number | null
  publishNote?: string | null
}

export type DryRunResult = {
  matched: boolean
  decision: string
  reason: string
  versionId: string | null
  fellBackToStable: boolean
}

export type OrchDecision = {
  id: string
  eventId: string
  ruleVersionId: string
  ruleId?: string | null
  strength: 'EXPLICIT' | 'INFERRED'
  decision: 'ACCEPT' | 'IGNORE' | 'STOP' | 'UNAVAILABLE' | 'WOULD_ACCEPT'
  reason?: string | null
  workItemId?: string | null
  subjectKey?: string | null
  createAt: string
}

export type WorkItemStatus = 'OPEN' | 'CLOSED' | 'STOPPED'

export type MandateView = {
  id: string
  ruleVersionId: string
  serviceAccountId: string
  strength: 'EXPLICIT' | 'INFERRED'
  via: string
  status: 'ACTIVE' | 'REVOKED'
  requesterUserId?: string | null
  revokedBy?: string | null
  revokedReason?: string | null
  createAt: string
}

export type OrchRunStatus =
  | 'PENDING'
  | 'DISPATCHED'
  | 'RUNNING'
  | 'WAITING_ATTENTION'
  | 'UNCERTAIN'
  | 'SUCCEEDED'
  | 'FAILED'
  | 'CANCELLED'

export type OrchRunSummary = {
  id: string
  status: OrchRunStatus
  attempt: number
  threadId?: string | null
  turnId?: string | null
  createAt: string
  endedAt?: string | null
}

export type WorkItemView = {
  id: string
  workspaceId?: string | null
  ruleId: string
  ruleName?: string | null
  subjectKey: string
  status: WorkItemStatus
  activeRunId?: string | null
  threadId?: string | null
  lastEventAt?: string | null
  mandate?: MandateView | null
  runs: OrchRunSummary[]
  createAt: string
  updateAt: string
}

export type WhyFact = {
  seq: number
  at: string
  kind: string
  detail: string
}

export type OrchRunView = {
  id: string
  workItemId: string
  status: OrchRunStatus
  attempt: number
  threadId?: string | null
  facts: WhyFact[]
}

/** 测试可强制 fixtures；生产默认 false，失败时回退。 */
let useOrchFixtures = false

export function setUseOrchFixtures(v: boolean): void {
  useOrchFixtures = v
}

export function getUseOrchFixtures(): boolean {
  return useOrchFixtures
}

function toQuery(params: Record<string, string | number | undefined>): string {
  const q = new URLSearchParams()
  for (const [k, v] of Object.entries(params)) {
    if (v !== undefined && v !== '') q.set(k, String(v))
  }
  const s = q.toString()
  return s ? `?${s}` : ''
}

/** 后端 VersionView 用 actionsJson 字符串；前端统一为 actions 数组。 */
type ApiVersionRaw = Omit<OrchRuleVersion, 'actions' | 'emits'> & {
  actions?: OrchRuleAction[]
  actionsJson?: string
  emits?: string[]
  emitsJson?: string
}

type ApiRuleRaw = Omit<OrchRule, 'versions'> & {
  versions?: ApiVersionRaw[]
}

function parseJsonArray<T>(raw: string | null | undefined, fallback: T[]): T[] {
  if (!raw) return fallback
  try {
    const v = JSON.parse(raw) as unknown
    return Array.isArray(v) ? (v as T[]) : fallback
  } catch {
    return fallback
  }
}

function normalizeVersion(v: ApiVersionRaw): OrchRuleVersion {
  const actions =
    v.actions ??
    parseJsonArray<OrchRuleAction>(v.actionsJson, [])
  const emits = v.emits ?? parseJsonArray<string>(v.emitsJson, [])
  return {
    id: v.id,
    ruleId: v.ruleId,
    version: v.version,
    eventType: v.eventType,
    conditionCel: v.conditionCel,
    actions,
    concurrency: v.concurrency,
    acceptMode: v.acceptMode,
    tell: v.tell,
    emits,
    serviceAccountId: v.serviceAccountId,
    stage: v.stage,
    canaryPercent: v.canaryPercent,
    schedule: v.schedule ?? null,
    contentHash: v.contentHash,
    publishNote: v.publishNote,
    previousEffectiveVersionId: v.previousEffectiveVersionId,
    publishedBy: v.publishedBy,
    publishedAt: v.publishedAt
  }
}

function normalizeRule(r: ApiRuleRaw): OrchRule {
  return {
    ...r,
    versions: (r.versions ?? []).map(normalizeVersion)
  }
}

function cloneRulesFixture(): OrchRule[] {
  return (rulesFixture as OrchRule[]).map((r) =>
    normalizeRule(JSON.parse(JSON.stringify(r)) as ApiRuleRaw)
  )
}

export async function listRules(): Promise<OrchRule[]> {
  if (useOrchFixtures) return cloneRulesFixture()
  try {
    const raw = await api<ApiRuleRaw[]>('/api/orch/rules')
    return raw.map(normalizeRule)
  } catch {
    return cloneRulesFixture()
  }
}

export async function getRule(id: string): Promise<OrchRule> {
  if (useOrchFixtures) {
    const hit = cloneRulesFixture().find((r) => r.id === id)
    if (!hit) throw new Error('rule 不存在')
    return hit
  }
  try {
    return normalizeRule(await api<ApiRuleRaw>(`/api/orch/rules/${encodeURIComponent(id)}`))
  } catch {
    const hit = cloneRulesFixture().find((r) => r.id === id)
    if (!hit) throw new Error('rule 不存在')
    return hit
  }
}

export async function createRule(name: string): Promise<OrchRule> {
  if (useOrchFixtures) {
    return {
      id: `rule_local_${Date.now()}`,
      name,
      workspaceId: 'ws_demo',
      ownerId: 'usr_local',
      versions: []
    }
  }
  return normalizeRule(
    await api<ApiRuleRaw>('/api/orch/rules', {
      method: 'POST',
      body: JSON.stringify({ name })
    })
  )
}

export async function createVersion(
  ruleId: string,
  body: CreateVersionBody
): Promise<OrchRuleVersion> {
  if (useOrchFixtures) {
    if (/event\.body/.test(body.conditionCel)) {
      throw Object.assign(new Error('CEL 不得访问 event.body（行列 1:1）'), {
        status: 400,
        code: 40000
      })
    }
    return {
      id: `rv_local_${Date.now()}`,
      ruleId,
      version: 99,
      eventType: body.eventType,
      conditionCel: body.conditionCel,
      actions: body.actions,
      concurrency: body.concurrency,
      acceptMode: body.acceptMode,
      tell: body.tell,
      emits: body.emits,
      serviceAccountId: body.serviceAccountId,
      stage: 'DRAFT',
      canaryPercent: null,
      schedule: null
    }
  }
  const raw = await api<ApiVersionRaw>(
    `/api/orch/rules/${encodeURIComponent(ruleId)}/versions`,
    { method: 'POST', body: JSON.stringify(body) }
  )
  return normalizeVersion(raw)
}

export async function publishVersion(
  ruleId: string,
  versionId: string,
  body: PublishBody
): Promise<OrchRuleVersion> {
  if (useOrchFixtures) {
    const rules = cloneRulesFixture()
    const rule = rules.find((r) => r.id === ruleId)
    const ver = rule?.versions?.find((v) => v.id === versionId)
    if (!ver) throw new Error('rule version 不存在')
    return {
      ...ver,
      stage: body.stage,
      canaryPercent: body.stage === 'CANARY' ? (body.canaryPercent ?? 10) : null,
      publishNote: body.publishNote ?? null
    }
  }
  const raw = await api<ApiVersionRaw>(
    `/api/orch/rules/${encodeURIComponent(ruleId)}/versions/${encodeURIComponent(versionId)}/publish`,
    { method: 'POST', body: JSON.stringify(body) }
  )
  return normalizeVersion(raw)
}

export async function dryRunVersion(
  ruleId: string,
  versionId: string,
  event: Record<string, unknown>
): Promise<DryRunResult> {
  if (useOrchFixtures) {
    return {
      matched: true,
      decision: 'WOULD_ACCEPT',
      reason: 'fixture_dry_run',
      versionId,
      fellBackToStable: false
    }
  }
  try {
    return await api<DryRunResult>(
      `/api/orch/rules/${encodeURIComponent(ruleId)}/versions/${encodeURIComponent(versionId)}/dry-run`,
      { method: 'POST', body: JSON.stringify({ event }) }
    )
  } catch {
    return {
      matched: true,
      decision: 'WOULD_ACCEPT',
      reason: 'fixture_dry_run',
      versionId,
      fellBackToStable: false
    }
  }
}

export async function listDecisions(params: {
  ruleId?: string
  decision?: OrchDecision['decision']
} = {}): Promise<OrchDecision[]> {
  const fallback = (): OrchDecision[] => {
    let rows = decisionsFixture as OrchDecision[]
    if (params.ruleId) rows = rows.filter((d) => d.ruleId === params.ruleId)
    if (params.decision) rows = rows.filter((d) => d.decision === params.decision)
    return rows.map((d) => ({ ...d }))
  }

  if (useOrchFixtures) return fallback()
  try {
    return await api<OrchDecision[]>(`/api/orch/decisions${toQuery(params)}`)
  } catch {
    return fallback()
  }
}

export async function listWorkItems(params: {
  ruleId?: string
  status?: WorkItemStatus
} = {}): Promise<WorkItemView[]> {
  if (useOrchFixtures) {
    let rows = workItemsFixture as WorkItemView[]
    if (params.ruleId) rows = rows.filter((w) => w.ruleId === params.ruleId)
    if (params.status) rows = rows.filter((w) => w.status === params.status)
    return rows.map((w) => ({ ...w, runs: [...w.runs] }))
  }
  try {
    return await api<WorkItemView[]>(`/api/orch/work-items${toQuery(params)}`)
  } catch {
    let rows = workItemsFixture as WorkItemView[]
    if (params.ruleId) rows = rows.filter((w) => w.ruleId === params.ruleId)
    if (params.status) rows = rows.filter((w) => w.status === params.status)
    return rows.map((w) => ({ ...w, runs: [...w.runs] }))
  }
}

export async function getWorkItem(id: string): Promise<WorkItemView> {
  if (useOrchFixtures) {
    const hit = (workItemsFixture as WorkItemView[]).find((w) => w.id === id)
    if (!hit) throw new Error('work item 不存在')
    return { ...hit, runs: [...hit.runs] }
  }
  try {
    return await api<WorkItemView>(`/api/orch/work-items/${encodeURIComponent(id)}`)
  } catch {
    const hit = (workItemsFixture as WorkItemView[]).find((w) => w.id === id)
    if (!hit) throw new Error('work item 不存在')
    return { ...hit, runs: [...hit.runs] }
  }
}

export async function getRun(id: string): Promise<OrchRunView> {
  return api<OrchRunView>(`/api/orch/runs/${encodeURIComponent(id)}`)
}

export async function getRunFacts(id: string, afterSeq = 0): Promise<WhyFact[]> {
  return api<WhyFact[]>(
    `/api/orch/runs/${encodeURIComponent(id)}/facts?afterSeq=${afterSeq}`
  )
}
