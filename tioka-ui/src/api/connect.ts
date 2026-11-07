/**
 * FE-19 飞书账号绑定与「已连接的应用」（手写类型）。
 * 对齐 M2-03 CON-02 / FND-08。
 */
import { api } from './http'

export type ConnectedAppView = {
  id: string
  provider: string
  displayName: string
  status: 'ACTIVE' | 'REVOKED' | 'REFRESH_FAILED'
  scopes?: string | null
  createAt: string
  updateAt?: string | null
}

export type FeishuConnectStart = {
  authorizeUrl: string
}

export async function startFeishuConnect(bindingId: string): Promise<FeishuConnectStart> {
  return api<FeishuConnectStart>(
    `/api/connector/im/feishu/${encodeURIComponent(bindingId)}/oauth/authorize`
  )
}

export async function listConnectedApps(): Promise<ConnectedAppView[]> {
  return api<ConnectedAppView[]>('/api/connector/credentials')
}

export async function revokeConnectedApp(id: string): Promise<void> {
  await api(`/api/connector/credentials/${encodeURIComponent(id)}`, { method: 'DELETE' })
}
