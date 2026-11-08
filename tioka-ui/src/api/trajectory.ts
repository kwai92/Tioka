/**
 * HCI-07 轨迹 API（手写类型；OpenAPI 合入后换 schema.d.ts）。
 * 路径对齐 M2-04 TrajectoryApi。后端未合入前 4xx/5xx 回退 __fixtures__。
 */
import { api } from './http'
import threeStepTools from '@/views/trajectory/__fixtures__/three-step-tools.json'
import failover from '@/views/trajectory/__fixtures__/failover.json'
import approval from '@/views/trajectory/__fixtures__/approval.json'
import turnsList from '@/views/trajectory/__fixtures__/turns-list.json'

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
  /** 统一时间线入口（ADR-075 / M2-04 修订节） */
  requestId?: string | null
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

const FIXTURE_BY_TURN: Record<string, Trajectory> = {
  trn_tools_01: threeStepTools as Trajectory,
  trn_failover_01: failover as Trajectory,
  trn_approval_01: approval as Trajectory,
  trn_fixture_01: threeStepTools as Trajectory
}

const FIXTURE_TURNS = turnsList as TurnSummary[]

function resolveFixtureTrajectory(turnId: string): Trajectory {
  return FIXTURE_BY_TURN[turnId] ?? (threeStepTools as Trajectory)
}

export async function getTrajectory(turnId: string): Promise<Trajectory> {
  try {
    return await api<Trajectory>(`/api/runtime/turns/${encodeURIComponent(turnId)}/trajectory`)
  } catch {
    return resolveFixtureTrajectory(turnId)
  }
}

export async function listTurns(
  threadId: string,
  page = 0,
  size = 20
): Promise<TurnSummary[]> {
  try {
    return await api<TurnSummary[]>(
      `/api/runtime/threads/${encodeURIComponent(threadId)}/turns?page=${page}&size=${size}`
    )
  } catch {
    return FIXTURE_TURNS.slice(page * size, page * size + size)
  }
}

/** 导出 fixtures 供离线/单测引用 */
export const __trajectoryFixtures = {
  byTurn: FIXTURE_BY_TURN,
  turns: FIXTURE_TURNS
}
