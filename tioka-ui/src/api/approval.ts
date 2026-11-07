/**
 * HCI-05 审批台 API（手写类型；OpenAPI 合入后换 schema.d.ts）。
 * 路径对齐 M2-04 ApprovalQueryApi。
 */
import { api } from './http'

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

function toQuery(params: Record<string, string | number | undefined>): string {
  const q = new URLSearchParams()
  for (const [k, v] of Object.entries(params)) {
    if (v !== undefined && v !== '') q.set(k, String(v))
  }
  const s = q.toString()
  return s ? `?${s}` : ''
}

export async function listApprovals(query: ApprovalQuery = {}): Promise<ApprovalPage> {
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
  return api<ApprovalView>(`/api/runtime/approvals/${encodeURIComponent(id)}`)
}

export async function countApprovals(
  scope: ApprovalScope = 'ASSIGNED_TO_ME',
  status: ApprovalStatus = 'PENDING'
): Promise<number> {
  const data = await api<{ count: number } | number>(
    `/api/runtime/approvals/count${toQuery({ scope, status })}`
  )
  return typeof data === 'number' ? data : data.count
}

export async function batchDecide(body: BatchDecisionBody): Promise<BatchDecisionResult[]> {
  return api<BatchDecisionResult[]>('/api/runtime/approvals/batch-decision', {
    method: 'POST',
    body: JSON.stringify(body)
  })
}
