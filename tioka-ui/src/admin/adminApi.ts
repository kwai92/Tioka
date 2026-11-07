/** HCI-03：管理后台 API 封装（供页面 / vitest）。 */

import { api } from '@/api/http'

export type Page<T> = { items: T[]; total: number; page: number; size: number }

export type UserView = {
  id: string
  username: string
  displayName: string
  enabled: boolean
  idp: string
  mustChangePassword: boolean
}

export type WorkspaceView = { id: string; name: string; ownerUserId?: number }
export type MemberView = { userId: string; username: string | null; displayName: string | null; role: string }
export type RoleView = { code: string; name: string; scope: string; builtin: boolean; permissions: string[] }
export type GroupView = { id: string; name: string; source: string; memberIds: string[] }
export type AuditEvent = {
  id: string
  ts: string
  actorId: string
  action: string
  resourceType: string
  resourceId: string
  outcome: string
  reason?: string
}
export type QuotaView = { metric: string; limit: number | null; used: number; windowStart: string }
export type CostBucket = {
  key: string
  name: string
  promptTokens: number
  completionTokens: number
  costMicros: number
  calls: number
}
export type CostPoint = {
  day: string
  costMicros: number
  promptTokens?: number
  completionTokens?: number
  calls?: number
}
export type AclEntry = { subjectType: string; subjectId: string; subjectName?: string; level: string }

export function listUsers(q: string, page = 1, size = 20) {
  const qs = new URLSearchParams({ page: String(page), size: String(size) })
  if (q) qs.set('q', q)
  return api<Page<UserView>>(`/api/system/users?${qs}`)
}

export function createUser(body: { username: string; displayName?: string; password?: string }) {
  return api<{ id: string; initialPassword: string | null }>('/api/system/users', {
    method: 'POST',
    body: JSON.stringify(body)
  })
}

export function disableUser(id: string) {
  return api<null>(`/api/system/users/${id}/disable`, { method: 'PUT' })
}

export function enableUser(id: string) {
  return api<null>(`/api/system/users/${id}/enable`, { method: 'PUT' })
}

export function resetUserPassword(id: string) {
  return api<{ initialPassword: string }>(`/api/system/users/${id}/reset-password`, { method: 'PUT' })
}

export function listWorkspaces() {
  return api<WorkspaceView[]>('/api/system/workspaces')
}

export function createWorkspace(name: string) {
  return api<WorkspaceView>('/api/system/workspaces', {
    method: 'POST',
    body: JSON.stringify({ name })
  })
}

export function listMembers(workspaceId: string, page = 1, size = 20) {
  return api<Page<MemberView>>(
    `/api/system/workspaces/${workspaceId}/members?page=${page}&size=${size}`
  )
}

export function putMember(workspaceId: string, userId: string, role: string) {
  return api<null>(`/api/system/workspaces/${workspaceId}/members/${userId}`, {
    method: 'PUT',
    body: JSON.stringify({ role })
  })
}

export function removeMember(workspaceId: string, userId: string) {
  return api<null>(`/api/system/workspaces/${workspaceId}/members/${userId}`, { method: 'DELETE' })
}

export function listRoles() {
  return api<RoleView[]>('/api/system/roles')
}

export function listPermissionCatalog() {
  return api<string[]>('/api/system/permissions')
}

export function updateRole(code: string, body: { name?: string; permissions: string[] }) {
  return api<RoleView>(`/api/system/roles/${code}`, {
    method: 'PUT',
    body: JSON.stringify(body)
  })
}

export function createRole(body: { code: string; name: string; scope: string; permissions: string[] }) {
  return api<RoleView>('/api/system/roles', {
    method: 'POST',
    body: JSON.stringify(body)
  })
}

export function listGroups() {
  return api<GroupView[]>('/api/system/groups')
}

export function createGroup(name: string) {
  return api<GroupView>('/api/system/groups', {
    method: 'POST',
    body: JSON.stringify({ name })
  })
}

export function replaceGroupMembers(id: string, memberIds: string[]) {
  return api<GroupView>(`/api/system/groups/${id}/members`, {
    method: 'PUT',
    body: JSON.stringify({ memberIds })
  })
}

export function queryAudit(params: Record<string, string | undefined>) {
  const qs = new URLSearchParams()
  for (const [k, v] of Object.entries(params)) {
    if (v) qs.set(k, v)
  }
  return api<AuditEvent[]>(`/api/system/audit?${qs}`)
}

export function auditExportUrl(params: Record<string, string | undefined>): string {
  const qs = new URLSearchParams()
  for (const [k, v] of Object.entries(params)) {
    if (v) qs.set(k, v)
  }
  return `/api/system/audit/export?${qs}`
}

export function listQuotas(scopeType: string, scopeId: string) {
  return api<QuotaView[]>(
    `/api/system/quotas?scopeType=${encodeURIComponent(scopeType)}&scopeId=${encodeURIComponent(scopeId)}`
  )
}

export function putQuota(scopeType: string, scopeId: string, metric: string, limit: number | null) {
  return api<{ ok: boolean }>(`/api/system/quotas/${scopeType}/${scopeId}/${metric}`, {
    method: 'PUT',
    body: JSON.stringify({ limit })
  })
}

export function costSummary(groupBy: string, from?: string, to?: string) {
  const qs = new URLSearchParams({ groupBy })
  if (from) qs.set('from', from)
  if (to) qs.set('to', to)
  return api<CostBucket[]>(`/api/system/cost/summary?${qs}`)
}

export function costTrend(scopeType: string, scopeId: string, from?: string, to?: string) {
  const qs = new URLSearchParams({ scopeType, scopeId, interval: 'day' })
  if (from) qs.set('from', from)
  if (to) qs.set('to', to)
  return api<CostPoint[]>(`/api/system/cost/trend?${qs}`)
}

export function listAcl(type: string, id: string) {
  return api<AclEntry[]>(`/api/${type}/${id}/acl`)
}

export function replaceAcl(type: string, id: string, entries: AclEntry[]) {
  return api<null>(`/api/${type}/${id}/acl`, {
    method: 'PUT',
    body: JSON.stringify(entries)
  })
}

/** FND-06 契约（后端未落地时可失败）。 */
export function createServiceAccount(body: { name: string; workspaceId: string; role: string }) {
  return api<{ userId: string }>('/api/system/service-accounts', {
    method: 'POST',
    body: JSON.stringify(body)
  })
}

export function createApiKey(body: {
  serviceUserId: string
  name: string
  workspaceId?: string
  scopes?: string[]
  expiresAt?: string
}) {
  return api<{ id: string; key: string }>('/api/system/api-keys', {
    method: 'POST',
    body: JSON.stringify(body)
  })
}

export function listApiKeys() {
  return api<Array<{ id: string; name: string; prefix: string; lastUsedAt: string | null }>>(
    '/api/system/api-keys'
  )
}

export function deleteApiKey(id: string) {
  return api<null>(`/api/system/api-keys/${id}`, { method: 'DELETE' })
}

export function isOidcGroupSource(source: string | null | undefined): boolean {
  return !!source && source.startsWith('oidc:')
}
