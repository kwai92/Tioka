import { describe, expect, it, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { nextTick } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import ApprovalDesk from './ApprovalDesk.vue'
import { setUseApprovalFixtures } from '@/api/approval'
import { useAuthStore } from '@/stores/auth'

vi.mock('@/api/approval', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/approval')>()
  return {
    ...actual,
    streamApprovals: vi.fn(() => () => undefined)
  }
})

const stubs = {
  'el-button': {
    props: ['disabled', 'loading', 'type'],
    template: '<button :disabled="disabled" @click="$emit(\'click\')"><slot /></button>'
  },
  'el-tag': { template: '<span><slot /></span>' },
  'el-badge': { template: '<div><slot /></div>' },
  'el-tabs': { template: '<div><slot /></div>' },
  'el-tab-pane': { template: '<div><slot /></div>' },
  'el-select': { template: '<div><slot /></div>' },
  'el-option': true,
  'el-input': true,
  'el-input-number': true,
  'el-switch': true,
  'el-date-picker': true,
  'el-table': {
    props: ['data'],
    template:
      '<div class="table"><div v-for="r in data" :key="r.id" class="row" :data-id="r.id" :data-subject="r.subjectType">{{ r.title }}</div></div>'
  },
  'el-table-column': true,
  'el-checkbox': true,
  'el-drawer': { template: '<div v-if="modelValue"><slot /></div>', props: ['modelValue'] },
  'el-alert': {
    props: ['title'],
    template: '<div class="alert">{{ title }}<slot /></div>'
  },
  'el-empty': true,
  'el-form': { template: '<form><slot /></form>' },
  'el-form-item': { template: '<div><slot /></div>' },
  'el-radio-group': true,
  'el-radio-button': true
}

describe('HCI-05 ApprovalDesk 组件', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    const auth = useAuthStore()
    auth.accessToken = 'tka_test'
    auth.permissions = ['approval:decide']
    setUseApprovalFixtures(true)
  })

  it('列表覆盖三类主体且详情走注册表渲染', async () => {
    const { resolveApprovalSubjectRenderer, APPROVAL_SUBJECT_TYPES } = await import(
      './subjectRegistry'
    )
    const wrapper = mount(ApprovalDesk, { global: { stubs } })
    await flushPromises()
    await nextTick()

    const desk = wrapper.vm as unknown as { items: Array<{ subjectType: string }> }
    const types = [...new Set(desk.items.map((i) => i.subjectType))].sort()
    expect(types).toEqual(['flow_node_run', 'orch_run', 'turn'])
    for (const t of APPROVAL_SUBJECT_TYPES) {
      expect(resolveApprovalSubjectRenderer(t).name || resolveApprovalSubjectRenderer(t)).toBeTruthy()
    }
    expect(wrapper.text()).toMatch(/审批台/)
  })

  it('批量处理后展示逐条成败', async () => {
    const approval = await import('@/api/approval')
    vi.spyOn(approval, 'batchDecide').mockResolvedValue([
      { id: 'apr_turn_01', ok: true },
      { id: 'apr_orch_01', ok: false, errorCode: 'FORBIDDEN', message: '无权限' }
    ])

    const wrapper = mount(ApprovalDesk, { global: { stubs } })
    await flushPromises()

    const desk = wrapper.vm as unknown as {
      selectedIds: string[]
      runBatch: (approved: boolean) => Promise<void>
    }
    desk.selectedIds = ['apr_turn_01', 'apr_orch_01']
    await desk.runBatch(true)
    await flushPromises()

    expect(wrapper.text()).toMatch(/成功 1/)
    expect(wrapper.text()).toMatch(/失败 1/)
    expect(wrapper.text()).toMatch(/apr_orch_01/)
  })
})
