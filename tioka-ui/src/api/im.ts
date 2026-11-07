/**
 * FE-18 飞书接入 / IM 绑定管理（手写类型）。
 */
import { api } from './http'

export type ImBindingView = {
  id: string
  channel: 'feishu'
  name: string
  workspaceId: string
  defaultAgentId?: string | null
  orchEnabled: boolean
  enabled: boolean
  callbackUrl: string
  createAt: string
  updateAt?: string | null
}

export type ImBindingCreate = {
  name: string
  workspaceId: string
  appId: string
  appSecret: string
  encryptKey?: string
  verificationToken?: string
  defaultAgentId?: string
  orchEnabled?: boolean
}

export type ConnectivityCheckResult = {
  ok: boolean
  message?: string | null
}

export async function listImBindings(): Promise<ImBindingView[]> {
  return api<ImBindingView[]>('/api/connector/im/bindings')
}

export async function getImBinding(id: string): Promise<ImBindingView> {
  return api<ImBindingView>(`/api/connector/im/bindings/${encodeURIComponent(id)}`)
}

export async function createImBinding(body: ImBindingCreate): Promise<ImBindingView> {
  return api<ImBindingView>('/api/connector/im/bindings', {
    method: 'POST',
    body: JSON.stringify(body)
  })
}

export async function checkImBinding(id: string): Promise<ConnectivityCheckResult> {
  return api<ConnectivityCheckResult>(
    `/api/connector/im/bindings/${encodeURIComponent(id)}/connectivity`,
    { method: 'POST' }
  )
}
