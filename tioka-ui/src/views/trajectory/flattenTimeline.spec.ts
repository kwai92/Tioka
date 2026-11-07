import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import type { Trajectory } from '@/api/trajectory'
import { MAX_TRAJECTORY_ITEMS, countItems, flattenTimeline } from './flattenTimeline'

const here = dirname(fileURLToPath(import.meta.url))

function load(name: string): Trajectory {
  return JSON.parse(readFileSync(join(here, '__fixtures__', name), 'utf8')) as Trajectory
}

describe('flattenTimeline', () => {
  it('三步工具任务展平含模型条与工具对', () => {
    const rows = flattenTimeline(load('three-step-tools.json'))
    expect(rows.some((r) => r.kind === 'step' && r.step === 2)).toBe(true)
    expect(rows.some((r) => r.kind === 'model' && r.modelRef === 'gpt-mock@primary')).toBe(true)
    expect(rows.some((r) => r.item?.type === 'tool_call' && r.pairResult?.type === 'tool_result')).toBe(
      true
    )
    expect(rows.some((r) => r.item?.type === 'child_thread')).toBe(true)
  })

  it('故障接力步标记 failover，作废项保留', () => {
    const rows = flattenTimeline(load('failover.json'))
    expect(rows.some((r) => r.kind === 'model' && r.failover)).toBe(true)
    expect(rows.some((r) => r.item?.discarded)).toBe(true)
  })

  it('审批 fixture 含 card 项', () => {
    const rows = flattenTimeline(load('approval.json'))
    expect(rows.some((r) => r.item?.type === 'card')).toBe(true)
  })

  it('超过 MAX_TRAJECTORY_ITEMS 时截断', () => {
    const base = load('three-step-tools.json')
    const huge: Trajectory = {
      ...base,
      steps: [
        {
          step: 1,
          modelRef: 'm',
          failover: false,
          items: Array.from({ length: MAX_TRAJECTORY_ITEMS + 50 }, (_, i) => ({
            itemId: `itm_${i}`,
            seq: i + 1,
            step: 1,
            type: 'message',
            content: { text: `n${i}` },
            discarded: false
          }))
        }
      ]
    }
    expect(countItems(huge)).toBe(MAX_TRAJECTORY_ITEMS + 50)
    const rows = flattenTimeline(huge)
    const itemRows = rows.filter((r) => r.kind === 'item')
    expect(itemRows.length).toBeLessThanOrEqual(MAX_TRAJECTORY_ITEMS)
  })
})
