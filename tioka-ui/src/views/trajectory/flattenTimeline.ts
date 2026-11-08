/**
 * 将 Trajectory 步序列展平为虚拟滚动行；单 Turn 最多展示 MAX_ITEMS 个 item。
 */
import type { Trajectory, TrajectoryItemView, TrajectoryStep } from '@/api/trajectory'

export const MAX_TRAJECTORY_ITEMS = 2000

export type TimelineRowKind = 'step' | 'model' | 'item'

export type TimelineRow = {
  key: string
  kind: TimelineRowKind
  step: number
  modelRef?: string | null
  promptTokens?: number | null
  completionTokens?: number | null
  latencyMs?: number | null
  costMicros?: number | null
  failover?: boolean
  item?: TrajectoryItemView
  /** 与 tool_call 配对的 tool_result（折叠展示） */
  pairResult?: TrajectoryItemView
}

export function flattenTimeline(trajectory: Trajectory, maxItems = MAX_TRAJECTORY_ITEMS): TimelineRow[] {
  const rows: TimelineRow[] = []
  let itemCount = 0
  for (const step of trajectory.steps) {
    rows.push({
      key: `step-${step.step}`,
      kind: 'step',
      step: step.step
    })
    rows.push(modelRow(step))
    const paired = pairToolItems(step.items)
    for (const entry of paired) {
      if (itemCount >= maxItems) return rows
      rows.push({
        key: `item-${entry.item.itemId}`,
        kind: 'item',
        step: step.step,
        item: entry.item,
        pairResult: entry.pairResult
      })
      itemCount += 1
      if (entry.pairResult) itemCount += 1
      if (itemCount >= maxItems) return rows
    }
  }
  return rows
}

function modelRow(step: TrajectoryStep): TimelineRow {
  return {
    key: `model-${step.step}`,
    kind: 'model',
    step: step.step,
    modelRef: step.modelRef,
    promptTokens: step.promptTokens,
    completionTokens: step.completionTokens,
    latencyMs: step.latencyMs,
    costMicros: step.costMicros,
    failover: step.failover
  }
}

function pairToolItems(items: TrajectoryItemView[]): Array<{
  item: TrajectoryItemView
  pairResult?: TrajectoryItemView
}> {
  const used = new Set<string>()
  const out: Array<{ item: TrajectoryItemView; pairResult?: TrajectoryItemView }> = []
  for (let i = 0; i < items.length; i++) {
    const cur = items[i]
    if (used.has(cur.itemId)) continue
    if (cur.type === 'tool_call') {
      const result = items.find(
        (x, idx) =>
          idx > i &&
          !used.has(x.itemId) &&
          x.type === 'tool_result' &&
          String(x.content?.name ?? '') === String(cur.content?.name ?? '')
      ) ?? items.find((x, idx) => idx > i && !used.has(x.itemId) && x.type === 'tool_result')
      if (result) used.add(result.itemId)
      used.add(cur.itemId)
      out.push({ item: cur, pairResult: result })
      continue
    }
    if (cur.type === 'tool_result' && used.has(cur.itemId)) continue
    used.add(cur.itemId)
    out.push({ item: cur })
  }
  return out
}

export function countItems(trajectory: Trajectory): number {
  return trajectory.steps.reduce((n, s) => n + s.items.length, 0)
}
