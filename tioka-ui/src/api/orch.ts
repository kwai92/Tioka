/**
 * FE-15 / FE-16 编排 API（手写类型；OpenAPI 合入后换 schema.d.ts）。
 * 路径对齐 M2-02 规则与工单 REST。
 */
import { api } from './http'

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

function toQuery(params: Record<string, string | number | undefined>): string {
  const q = new URLSearchParams()
  for (const [k, v] of Object.entries(params)) {
    if (v !== undefined && v !== '') q.set(k, String(v))
  }
  const s = q.toString()
  return s ? `?${s}` : ''
}

export async function listRules(): Promise<OrchRule[]> {
  return api<OrchRule[]>('/api/orch/rules')
}

export async function getRule(id: string): Promise<OrchRule> {
  return api<OrchRule>(`/api/orch/rules/${encodeURIComponent(id)}`)
}

export async function listWorkItems(params: {
  ruleId?: string
  status?: WorkItemStatus
} = {}): Promise<WorkItemView[]> {
  return api<WorkItemView[]>(`/api/orch/work-items${toQuery(params)}`)
}

export async function getWorkItem(id: string): Promise<WorkItemView> {
  return api<WorkItemView>(`/api/orch/work-items/${encodeURIComponent(id)}`)
}

export async function getRun(id: string): Promise<OrchRunView> {
  return api<OrchRunView>(`/api/orch/runs/${encodeURIComponent(id)}`)
}

export async function getRunFacts(id: string, afterSeq = 0): Promise<WhyFact[]> {
  return api<WhyFact[]>(
    `/api/orch/runs/${encodeURIComponent(id)}/facts?afterSeq=${afterSeq}`
  )
}
