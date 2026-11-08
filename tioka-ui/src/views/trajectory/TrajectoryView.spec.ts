import { describe, expect, it, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { defineComponent, h, nextTick } from 'vue'
import type { Trajectory, TurnSummary } from '@/api/trajectory'

const here = dirname(fileURLToPath(import.meta.url))

function loadJson<T>(name: string): T {
  return JSON.parse(readFileSync(join(here, '__fixtures__', name), 'utf8')) as T
}

const fixtures: Record<string, Trajectory> = {
  trn_tools_01: loadJson('three-step-tools.json'),
  trn_failover_01: loadJson('failover.json'),
  trn_approval_01: loadJson('approval.json')
}
const turnsFixture = loadJson<TurnSummary[]>('turns-list.json')

vi.mock('@/api/trajectory', async () => {
  const actual = await vi.importActual<typeof import('@/api/trajectory')>('@/api/trajectory')
  return {
    ...actual,
    listTurns: vi.fn(async () => turnsFixture),
    getTrajectory: vi.fn(async (turnId: string) => {
      const t = fixtures[turnId]
      if (!t) throw new Error(`missing fixture ${turnId}`)
      return t
    })
  }
})

vi.mock('@/items/ItemRenderer.vue', () => ({
  default: defineComponent({
    name: 'ItemRendererStub',
    props: {
      item: { type: Object, required: true },
      pairResult: { type: Object, default: undefined },
      approval: { type: Object, default: undefined }
    },
    setup(props) {
      return () =>
        h(
          'div',
          { class: 'item-renderer-stub', 'data-type': (props.item as { itemType?: string }).itemType },
          [(props.item as { itemType?: string }).itemType]
        )
    }
  })
}))

import TrajectoryView from './TrajectoryView.vue'
import { flattenTimeline, MAX_TRAJECTORY_ITEMS } from './flattenTimeline'

const routeState = { turnId: 'trn_tools_01' as string | undefined }

vi.mock('vue-router', () => ({
  useRoute: () => ({
    params: { turnId: routeState.turnId },
    query: { threadId: 'thd_tools_01' }
  }),
  useRouter: () => ({
    replace: vi.fn(async (loc: { params?: { turnId?: string } }) => {
      if (loc.params?.turnId) routeState.turnId = loc.params.turnId
    })
  })
}))

const elStubs = {
  'el-button': { template: '<button class="el-button-stub" v-bind="$attrs"><slot /></button>' },
  'el-tag': { template: '<span class="el-tag-stub"><slot /></span>' }
}

describe('HCI-07 TrajectoryView snapshots', () => {
  beforeEach(() => {
    routeState.turnId = 'trn_tools_01'
  })

  const cases: Array<{ name: string; turnId: string }> = [
    { name: '三步工具任务', turnId: 'trn_tools_01' },
    { name: '含故障接力', turnId: 'trn_failover_01' },
    { name: '含审批', turnId: 'trn_approval_01' }
  ]

  for (const c of cases) {
    it(`${c.name} 快照`, async () => {
      routeState.turnId = c.turnId
      const wrapper = mount(TrajectoryView, {
        global: { stubs: elStubs },
        attachTo: document.body
      })
      await flushPromises()
      await nextTick()
      expect(wrapper.find('[data-testid="trajectory-view"]').exists()).toBe(true)
      expect(wrapper.html()).toMatchSnapshot()
      wrapper.unmount()
    })
  }
})

describe('HCI-07 TrajectoryView 行为', () => {
  beforeEach(() => {
    routeState.turnId = 'trn_tools_01'
  })

  it('左侧 Turn 列表展示状态/步数/tokens', async () => {
    const wrapper = mount(TrajectoryView, { global: { stubs: elStubs } })
    await flushPromises()
    const list = wrapper.find('[data-testid="turn-list"]')
    expect(list.exists()).toBe(true)
    expect(list.text()).toContain('SUCCEEDED')
    expect(list.text()).toMatch(/步/)
    expect(list.text()).toMatch(/token/i)
    wrapper.unmount()
  })

  it('故障接力条与作废标注可见', async () => {
    routeState.turnId = 'trn_failover_01'
    const wrapper = mount(TrajectoryView, { global: { stubs: elStubs } })
    await flushPromises()
    expect(wrapper.text()).toContain('故障接力')
    expect(wrapper.text()).toContain('已作废（模型切换）')
    wrapper.unmount()
  })

  it('审批人与时间可见；无 traceUrl 时不显示 Jaeger', async () => {
    routeState.turnId = 'trn_approval_01'
    const wrapper = mount(TrajectoryView, { global: { stubs: elStubs } })
    await flushPromises()
    expect(wrapper.text()).toMatch(/Alice|user_alice/)
    expect(wrapper.text()).toContain('2026-11-07')
    expect(wrapper.find('[data-testid="jaeger-link"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('traceUrl 非空时显示 Jaeger 链接', async () => {
    routeState.turnId = 'trn_tools_01'
    const wrapper = mount(TrajectoryView, { global: { stubs: elStubs } })
    await flushPromises()
    const link = wrapper.find('[data-testid="jaeger-link"]')
    expect(link.exists()).toBe(true)
    expect(link.attributes('href')).toContain('jaeger.local')
    wrapper.unmount()
  })

  it('显示「查看完整时间线」入口（按 requestId）', async () => {
    const wrapper = mount(TrajectoryView, { global: { stubs: elStubs } })
    await flushPromises()
    const tl = wrapper.find('[data-testid="timeline-link"]')
    expect(tl.exists()).toBe(true)
    expect(tl.attributes('href')).toContain('req_tools_01')
    wrapper.unmount()
  })

  it('未知 item 类型走 ItemRenderer（F26）', async () => {
    routeState.turnId = 'trn_failover_01'
    const wrapper = mount(TrajectoryView, { global: { stubs: elStubs } })
    await flushPromises()
    const stubs = wrapper.findAll('.item-renderer-stub')
    expect(stubs.some((n) => n.attributes('data-type') === 'data')).toBe(true)
    wrapper.unmount()
  })

  it('2000 项展平后虚拟列表仅挂载可视行', async () => {
    const base = fixtures.trn_tools_01
    const huge: Trajectory = {
      ...base,
      turn: { ...base.turn, turnId: 'trn_huge', stepCount: 1 },
      steps: [
        {
          step: 1,
          modelRef: 'gpt-mock@primary',
          failover: false,
          promptTokens: 1,
          completionTokens: 1,
          latencyMs: 1,
          costMicros: 1,
          items: Array.from({ length: MAX_TRAJECTORY_ITEMS }, (_, i) => ({
            itemId: `itm_h_${i}`,
            seq: i + 1,
            step: 1,
            type: 'message',
            role: 'assistant',
            content: { text: `row-${i}` },
            discarded: false
          }))
        }
      ]
    }
    const rows = flattenTimeline(huge)
    expect(rows.filter((r) => r.kind === 'item').length).toBe(MAX_TRAJECTORY_ITEMS)

    routeState.turnId = 'trn_tools_01'
    const wrapper = mount(TrajectoryView, {
      props: { trajectoryOverride: huge, turnsOverride: turnsFixture },
      global: { stubs: elStubs },
      attachTo: document.body
    })
    await flushPromises()
    await nextTick()
    const scroller = wrapper.find('[data-testid="virtual-list"]')
    expect(scroller.exists()).toBe(true)
    const rendered = wrapper.findAll('[data-testid="timeline-row"]')
    expect(rendered.length).toBeGreaterThan(0)
    expect(rendered.length).toBeLessThan(400)
    wrapper.unmount()
  })
})
