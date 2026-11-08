/**
 * FE-17 运维 API：死信与定时器（手写类型）。
 * 死信路径对齐 M2-01；定时器对齐 M2-02。
 */
import { api } from './http'

export type DeadLetterStatus = 'OPEN' | 'REPLAYED' | 'DISCARDED'

export type DeadLetterView = {
  id: string
  consumer: string
  topic?: string | null
  eventType?: string | null
  status: DeadLetterStatus
  payloadPreview: string
  errorMessage?: string | null
  createAt: string
  updateAt?: string | null
}

export type DeadLetterPage = {
  items: DeadLetterView[]
  total: number
  page: number
  size: number
}

export type OrchTimerView = {
  id: string
  workItemId: string
  timerKey: string
  ruleId: string
  fireAt: string
  status: 'SCHEDULED' | 'FIRED' | 'CANCELLED'
  createAt: string
}

function toQuery(params: Record<string, string | number | undefined>): string {
  const q = new URLSearchParams()
  for (const [k, v] of Object.entries(params)) {
    if (v !== undefined && v !== '') q.set(k, String(v))
  }
  const s = q.toString()
  return s ? `?${s}` : ''
}

export async function listDeadLetters(params: {
  consumer?: string
  status?: DeadLetterStatus
  page?: number
  size?: number
} = {}): Promise<DeadLetterPage> {
  return api<DeadLetterPage>(`/api/ops/dead-letters${toQuery(params)}`)
}

export async function replayDeadLetter(id: string): Promise<void> {
  await api(`/api/ops/dead-letters/${encodeURIComponent(id)}/replay`, { method: 'POST' })
}

export async function discardDeadLetter(id: string, reason: string): Promise<void> {
  await api(`/api/ops/dead-letters/${encodeURIComponent(id)}/discard`, {
    method: 'POST',
    body: JSON.stringify({ reason })
  })
}

export async function listTimers(): Promise<OrchTimerView[]> {
  return api<OrchTimerView[]>('/api/orch/timers')
}
