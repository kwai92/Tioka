import { describe, expect, it, beforeEach } from 'vitest'
import {
  applyApprovalBadgeEvent,
  buildApprovalListQuery,
  filterApprovalFixturePage,
  formatBatchResults,
  listApprovals,
  setUseApprovalFixtures,
  type ApprovalStreamEvent,
  type BatchDecisionResult
} from './approval'

describe('HCI-05 列表筛选参数映射', () => {
  it('UI 筛选映射为 ApprovalQuery', () => {
    const q = buildApprovalListQuery({
      scope: 'ASSIGNED_TO_ME',
      status: 'PENDING',
      subjectType: 'orch_run',
      agentId: 'agt_1',
      risk: 'HIGH',
      from: '2026-11-01T00:00:00Z',
      to: '2026-11-08T00:00:00Z',
      keyword: '退款',
      page: 1,
      size: 20
    })
    expect(q).toEqual({
      scope: 'ASSIGNED_TO_ME',
      status: 'PENDING',
      subjectType: 'orch_run',
      agentId: 'agt_1',
      risk: 'HIGH',
      from: '2026-11-01T00:00:00Z',
      to: '2026-11-08T00:00:00Z',
      keyword: '退款',
      page: 1,
      size: 20
    })
  })

  it('空筛选不带多余字段', () => {
    const q = buildApprovalListQuery({ scope: 'REQUESTED_BY_ME' })
    expect(q).toEqual({ scope: 'REQUESTED_BY_ME' })
  })

  it('fixtures 按 subjectType / risk / keyword 过滤', async () => {
    setUseApprovalFixtures(true)
    const page = await listApprovals({
      subjectType: 'turn',
      risk: 'MEDIUM',
      keyword: 'echo'
    })
    expect(page.items).toHaveLength(1)
    expect(page.items[0].id).toBe('apr_turn_01')
    expect(page.total).toBe(1)
  })

  it('fixtures 过滤函数与 query 一致', () => {
    const filtered = filterApprovalFixturePage({
      subjectType: 'flow_node_run',
      status: 'PENDING'
    })
    expect(filtered.items.map((i) => i.subjectType)).toEqual(['flow_node_run'])
  })
})

describe('HCI-05 批量结果展示', () => {
  it('汇总成功与失败条数并保留逐条结果', () => {
    const results: BatchDecisionResult[] = [
      { id: 'apr_1', ok: true },
      { id: 'apr_2', ok: false, errorCode: 'ALREADY_DECIDED', message: '已处理' },
      { id: 'apr_3', ok: true }
    ]
    const summary = formatBatchResults(results)
    expect(summary.successCount).toBe(2)
    expect(summary.failureCount).toBe(1)
    expect(summary.lines).toEqual([
      { id: 'apr_1', ok: true, text: 'apr_1 · 成功' },
      { id: 'apr_2', ok: false, text: 'apr_2 · 失败 ALREADY_DECIDED：已处理' },
      { id: 'apr_3', ok: true, text: 'apr_3 · 成功' }
    ])
  })
})

describe('HCI-05 SSE 增量更新角标', () => {
  beforeEach(() => {
    setUseApprovalFixtures(true)
  })

  it('approval.requested 使待办角标 +1', () => {
    const next = applyApprovalBadgeEvent(3, {
      type: 'approval.requested',
      approval: { id: 'apr_x', status: 'PENDING' }
    } as ApprovalStreamEvent)
    expect(next).toBe(4)
  })

  it('approval.decided（飞书/Web 多端同步）使待办角标 -1 且不小于 0', () => {
    expect(
      applyApprovalBadgeEvent(1, {
        type: 'approval.decided',
        approval: { id: 'apr_x', status: 'APPROVED' }
      } as ApprovalStreamEvent)
    ).toBe(0)
    expect(
      applyApprovalBadgeEvent(0, {
        type: 'approval.decided',
        approval: { id: 'apr_y', status: 'REJECTED' }
      } as ApprovalStreamEvent)
    ).toBe(0)
  })

  it('无关事件不改角标', () => {
    expect(applyApprovalBadgeEvent(2, { type: 'ping' } as ApprovalStreamEvent)).toBe(2)
  })
})
