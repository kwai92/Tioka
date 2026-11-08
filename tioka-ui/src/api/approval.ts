/**
 * HCI-05 审批台 API（手写类型；OpenAPI 合入后换 schema.d.ts）。
 * 路径对齐 M2-04 ApprovalQueryApi。
 * 后端 HCI-05b 未合入前默认走 __fixtures__。
 */
import { fetchEventSource } from '@microsoft/fetch-event-source'
import { api, authHeaders } from './http'
import fixturePage from '@/__fixtures__/approval-desk-response.json'

export type ApprovalSubjectType = 'turn' | 'orch_run' | 'flow_node_run'

export type ApprovalStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'EXPIRED' | 'CANCELLED'

export type ApprovalScope = 'ASSIGNED_TO_ME' | 'REQUESTED_BY_ME' | 'WORKSPACE_ALL'

export type ToolRisk = 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL'

export type SubjectLink = {
  type: string
  id: string
  title: string
  url: string
}

export type ApprovalView = {
  id: string
  subjectType: ApprovalSubjectType
  subjectId: string
  kind: string
  title: string
  summary?: string | null
  toolName?: string | null
  args?: Record<string, unknown> | null
  card?: Record<string, unknown> | null
  risk?: ToolRisk | null
  status: ApprovalStatus
  workspaceId?: string | null
  requestedBy?: string | null
  requestedByName?: string | null
  assigneeUserIds?: string[]
  assigneeRoles?: string[]
  decidedBy?: string | null
  decidedByName?: string | null
  decidedAt?: string | null
  answer?: Record<string, unknown> | null
  comment?: string | null
  expiresAt?: string | null
  createdAt: string
  link?: SubjectLink | null
}

export type ApprovalQuery = {
  scope?: ApprovalScope
  status?: ApprovalStatus
  subjectType?: ApprovalSubjectType
  agentId?: string
  risk?: ToolRisk
  from?: string
  to?: string
  keyword?: string
  page?: number
  size?: number
}

export type ApprovalPage = {
  items: ApprovalView[]
  total: number
  page: number
  size: number
}

export type BatchDecisionBody = {
  ids: string[]
  approved: boolean
  comment?: string
}

export type BatchDecisionResult = {
  id: string
  ok: boolean
  errorCode?: string | null
  message?: string | null
}

export type BatchResultSummary = {
  successCount: number
  failureCount: number
  lines: Array<{ id: string; ok: boolean; text: string }>
}

export type ApprovalStreamEvent = {
  type: string
  approval?: Partial<ApprovalView> & { id?: string; status?: ApprovalStatus }
}

/** 后端未合入前为 true；合入后改为 false 或由环境切换。 */
let useApprovalFixtures = true

export function setUseApprovalFixtures(v: boolean): void {
  useApprovalFixtures = v
}

export function isUsingApprovalFixtures(): boolean {
  return useApprovalFixtures
}

/** UI 筛选 → ApprovalQuery（去掉空值）。 */
export function buildApprovalListQuery(
  filters: Partial<ApprovalQuery> & { scope?: ApprovalScope }
): ApprovalQuery {
  const q: ApprovalQuery = {}
  if (filters.scope) q.scope = filters.scope
  if (filters.status) q.status = filters.status
  if (filters.subjectType) q.subjectType = filters.subjectType
  if (filters.agentId) q.agentId = filters.agentId
  if (filters.risk) q.risk = filters.risk
  if (filters.from) q.from = filters.from
  if (filters.to) q.to = filters.to
  if (filters.keyword) q.keyword = filters.keyword
  if (filters.page !== undefined) q.page = filters.page
  if (filters.size !== undefined) q.size = filters.size
  return q
}

export function filterApprovalFixturePage(query: ApprovalQuery = {}): ApprovalPage {
  const raw = fixturePage as ApprovalPage
  let items = [...raw.items]

  if (query.status) {
    items = items.filter((i) => i.status === query.status)
  }
  if (query.subjectType) {
    items = items.filter((i) => i.subjectType === query.subjectType)
  }
  if (query.risk) {
    items = items.filter((i) => i.risk === query.risk)
  }
  if (query.keyword) {
    const kw = query.keyword.toLowerCase()
    items = items.filter(
      (i) =>
        i.title.toLowerCase().includes(kw) ||
        (i.summary ?? '').toLowerCase().includes(kw) ||
        (i.toolName ?? '').toLowerCase().includes(kw)
    )
  }
  if (query.from) {
    const from = Date.parse(query.from)
    items = items.filter((i) => Date.parse(i.createdAt) >= from)
  }
  if (query.to) {
    const to = Date.parse(query.to)
    items = items.filter((i) => Date.parse(i.createdAt) <= to)
  }

  const page = query.page ?? 0
  const size = query.size ?? 20
  const start = page * size
  const sliced = items.slice(start, start + size)
  return { items: sliced, total: items.length, page, size }
}

export function formatBatchResults(results: BatchDecisionResult[]): BatchResultSummary {
  const lines = results.map((r) => {
    if (r.ok) {
      return { id: r.id, ok: true, text: `${r.id} · 成功` }
    }
    const code = r.errorCode ? ` ${r.errorCode}` : ''
    const msg = r.message ? `：${r.message}` : ''
    return { id: r.id, ok: false, text: `${r.id} · 失败${code}${msg}` }
  })
  return {
    successCount: results.filter((r) => r.ok).length,
    failureCount: results.filter((r) => !r.ok).length,
    lines
  }
}

/** SSE / 飞书多端：requested +1，decided -1。 */
export function applyApprovalBadgeEvent(count: number, event: ApprovalStreamEvent): number {
  if (event.type === 'approval.requested') return count + 1
  if (event.type === 'approval.decided') return Math.max(0, count - 1)
  return count
}

function toQuery(params: Record<string, string | number | undefined>): string {
  const q = new URLSearchParams()
  for (const [k, v] of Object.entries(params)) {
    if (v !== undefined && v !== '') q.set(k, String(v))
  }
  const s = q.toString()
  return s ? `?${s}` : ''
}

export async function listApprovals(query: ApprovalQuery = {}): Promise<ApprovalPage> {
  if (useApprovalFixtures) {
    return filterApprovalFixturePage(query)
  }
  return api<ApprovalPage>(
    `/api/runtime/approvals${toQuery({
      scope: query.scope,
      status: query.status,
      subjectType: query.subjectType,
      agentId: query.agentId,
      risk: query.risk,
      from: query.from,
      to: query.to,
      keyword: query.keyword,
      page: query.page,
      size: query.size
    })}`
  )
}

export async function getApproval(id: string): Promise<ApprovalView> {
  if (useApprovalFixtures) {
    const page = filterApprovalFixturePage({})
    const found = page.items.find((i) => i.id === id)
    if (!found) throw new Error(`审批不存在：${id}`)
    return found
  }
  return api<ApprovalView>(`/api/runtime/approvals/${encodeURIComponent(id)}`)
}

export async function countApprovals(
  scope: ApprovalScope = 'ASSIGNED_TO_ME',
  status: ApprovalStatus = 'PENDING'
): Promise<number> {
  if (useApprovalFixtures) {
    return filterApprovalFixturePage({ scope, status }).total
  }
  const data = await api<{ count: number } | number>(
    `/api/runtime/approvals/count${toQuery({ scope, status })}`
  )
  return typeof data === 'number' ? data : data.count
}

export async function batchDecide(body: BatchDecisionBody): Promise<BatchDecisionResult[]> {
  if (useApprovalFixtures) {
    return body.ids.map((id) => ({ id, ok: true }))
  }
  return api<BatchDecisionResult[]>('/api/runtime/approvals/batch-decision', {
    method: 'POST',
    body: JSON.stringify(body)
  })
}

export type ApprovalStreamHandlers = {
  onEvent: (ev: ApprovalStreamEvent) => void
  onError?: (err: unknown) => void
  /** 可注入，便于单测 */
  fetch?: typeof fetch
}

/** 订阅审批 SSE；返回取消函数。fixtures 模式下空订阅。 */
export function streamApprovals(handlers: ApprovalStreamHandlers): () => void {
  if (useApprovalFixtures) {
    return () => undefined
  }
  const ctrl = new AbortController()
  void fetchEventSource('/api/runtime/approvals/stream', {
    signal: ctrl.signal,
    headers: authHeaders(),
    fetch: handlers.fetch,
    onmessage(msg) {
      if (!msg.data) return
      try {
        const ev = JSON.parse(msg.data) as ApprovalStreamEvent
        handlers.onEvent(ev)
      } catch (e) {
        handlers.onError?.(e)
      }
    },
    onerror(err) {
      handlers.onError?.(err)
      throw err
    }
  }).catch((e) => {
    if (!ctrl.signal.aborted) handlers.onError?.(e)
  })
  return () => ctrl.abort()
}
