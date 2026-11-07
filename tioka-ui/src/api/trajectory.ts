/**
 * HCI-07 轨迹 API（手写类型；OpenAPI 合入后换 schema.d.ts）。
 * 路径对齐 M2-04 TrajectoryApi。
 */
import { api } from './http'

export type TurnStatus =
  | 'QUEUED'
  | 'RUNNING'
  | 'WAITING_APPROVAL'
  | 'WAITING_CHILD'
  | 'SUCCEEDED'
  | 'FAILED'
  | 'CANCELLED'

export type TrajectoryItemView = {
  itemId: string
  threadId?: string
  turnId?: string
  seq: number
  step: number
  type: string
  role?: string | null
  content: Record<string, unknown>
  createdAt?: string | null
  /** 作废项（模型切换）灰显 */
  discarded?: boolean
}

export type TrajectoryStep = {
  step: number
  items: TrajectoryItemView[]
  modelRef?: string | null
  promptTokens?: number | null
  completionTokens?: number | null
  latencyMs?: number | null
  costMicros?: number | null
  failover: boolean
}

export type TurnView = {
  turnId: string
  threadId: string
  seq: number
  status: TurnStatus
  origin?: string | null
  originRef?: string | null
  stepCount: number
  errorCode?: string | null
  errorMessage?: string | null
  promptTokens?: number | null
  completionTokens?: number | null
  createdAt?: string | null
  startedAt?: string | null
  endedAt?: string | null
}

export type Trajectory = {
  turn: TurnView
  steps: TrajectoryStep[]
  traceUrl?: string | null
}

export type TurnSummary = {
  turnId: string
  seq: number
  status: TurnStatus
  origin?: string | null
  stepCount: number
  promptTokens?: number | null
  completionTokens?: number | null
  costMicros?: number | null
  startedAt?: string | null
  endedAt?: string | null
}

export async function getTrajectory(turnId: string): Promise<Trajectory> {
  return api<Trajectory>(`/api/runtime/turns/${encodeURIComponent(turnId)}/trajectory`)
}

export async function listTurns(
  threadId: string,
  page = 0,
  size = 20
): Promise<TurnSummary[]> {
  return api<TurnSummary[]>(
    `/api/runtime/threads/${encodeURIComponent(threadId)}/turns?page=${page}&size=${size}`
  )
}
