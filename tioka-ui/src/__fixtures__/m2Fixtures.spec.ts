import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import type { ApprovalPage } from '@/api/approval'
import type { Trajectory } from '@/api/trajectory'
import type { OrchRule, WorkItemView } from '@/api/orch'

const here = dirname(fileURLToPath(import.meta.url))

function load<T>(name: string): T {
  return JSON.parse(readFileSync(join(here, name), 'utf8')) as T
}

describe('PK-00d M2 fixtures', () => {
  it('轨迹响应含步、故障接力与未知 item 类型', () => {
    const traj = load<Trajectory>('trajectory-response.json')
    expect(traj.turn.turnId).toBeTruthy()
    expect(traj.steps).toHaveLength(3)
    expect(traj.steps.some((s) => s.failover)).toBe(true)
    const types = traj.steps.flatMap((s) => s.items.map((i) => i.type))
    expect(types).toContain('data')
    expect(traj.steps.flatMap((s) => s.items).some((i) => i.discarded)).toBe(true)
  })

  it('审批台响应覆盖三类主体', () => {
    const page = load<ApprovalPage>('approval-desk-response.json')
    expect(page.items.map((i) => i.subjectType).sort()).toEqual([
      'flow_node_run',
      'orch_run',
      'turn'
    ])
    expect(page.items.every((i) => i.workspaceId && !('tenantId' in i))).toBe(true)
  })

  it('规则与工单响应可解析', () => {
    const rules = load<OrchRule[]>('orch-rules-response.json')
    const items = load<WorkItemView[]>('orch-work-items-response.json')
    expect(rules[0].versions?.[0].acceptMode).toBe('auto')
    expect(items[0].mandate?.status).toBe('ACTIVE')
    expect(items[0].runs[0].status).toBe('WAITING_ATTENTION')
  })
})
