import { describe, expect, it, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { nextTick } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import RulesView from './RulesView.vue'
import { setUseOrchFixtures } from '@/api/orch'
import { useAuthStore } from '@/stores/auth'
import { parseCelError } from './parseCelError'

vi.mock('@/api/orch', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/orch')>()
  return {
    ...actual,
    listRules: vi.fn(actual.listRules),
    createRule: vi.fn(actual.createRule),
    createVersion: vi.fn(actual.createVersion),
    publishVersion: vi.fn(actual.publishVersion),
    dryRunVersion: vi.fn(actual.dryRunVersion),
    listDecisions: vi.fn(actual.listDecisions)
  }
})

const stubs = {
  'el-button': {
    props: ['disabled', 'loading', 'type'],
    template: '<button :disabled="disabled" @click="$emit(\'click\')"><slot /></button>'
  },
  'el-tag': { template: '<span class="el-tag"><slot /></span>' },
  'el-alert': {
    props: ['title', 'type'],
    template: '<div class="alert" :data-type="type">{{ title }}<slot /></div>'
  },
  'el-empty': { template: '<div class="empty"><slot /></div>' },
  'el-table': {
    props: ['data'],
    template:
      '<div class="table"><div v-for="r in data" :key="r.id" class="row" :data-id="r.id">{{ r.name }}</div></div>'
  },
  'el-table-column': true,
  'el-form': { template: '<form><slot /></form>' },
  'el-form-item': { template: '<div class="form-item"><slot /></div>' },
  'el-input': {
    props: ['modelValue', 'type', 'rows'],
    emits: ['update:modelValue'],
    template:
      '<textarea v-if="type===\'textarea\'" class="el-input" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" /><input v-else class="el-input" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />'
  },
  'el-input-number': {
    props: ['modelValue'],
    emits: ['update:modelValue'],
    template:
      '<input class="el-input-number" type="number" :value="modelValue" @input="$emit(\'update:modelValue\', Number($event.target.value))" />'
  },
  'el-select': { template: '<div class="el-select"><slot /></div>' },
  'el-option': true,
  'el-radio-group': { template: '<div class="el-radio-group"><slot /></div>' },
  'el-radio': { template: '<label><slot /></label>' },
  'el-tabs': { template: '<div class="tabs"><slot /></div>' },
  'el-tab-pane': { template: '<div class="tab-pane"><slot /></div>' },
  'el-dialog': {
    props: ['modelValue'],
    template: '<div v-if="modelValue" class="dialog"><slot /><slot name="footer" /></div>'
  },
  'el-drawer': {
    props: ['modelValue'],
    template: '<div v-if="modelValue" class="drawer"><slot /></div>'
  }
}

describe('FE-15 RulesView', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    const auth = useAuthStore()
    auth.accessToken = 'tka_test'
    auth.permissions = ['orch:read', 'orch:write']
    setUseOrchFixtures(true)
  })

  it('加载规则列表（fixtures）', async () => {
    const wrapper = mount(RulesView, { global: { stubs } })
    await flushPromises()
    await nextTick()
    expect(wrapper.text()).toMatch(/编排规则/)
    expect(wrapper.find('[data-testid="rules-view"]').exists()).toBe(true)
    const vm = wrapper.vm as unknown as { rules: Array<{ id: string }> }
    expect(vm.rules.length).toBeGreaterThan(0)
    expect(vm.rules[0].id).toBe('rule_fixture_01')
  })

  it('CEL 编译错误在编辑器上定位到行列', async () => {
    const orch = await import('@/api/orch')
    vi.mocked(orch.createVersion).mockRejectedValueOnce(
      Object.assign(new Error('CEL 编译失败（行列 3:5）: syntax error'), {
        status: 400,
        code: 40000
      })
    )

    const wrapper = mount(RulesView, { global: { stubs } })
    await flushPromises()
    await nextTick()

    const vm = wrapper.vm as unknown as {
      selectedRuleId: string | null
      openDraft: (ruleId: string) => void
      submitDraft: () => Promise<void>
      celError: ReturnType<typeof parseCelError>
    }
    vm.openDraft('rule_fixture_01')
    await nextTick()
    await vm.submitDraft()
    await flushPromises()

    expect(vm.celError?.line).toBe(3)
    expect(vm.celError?.column).toBe(5)
    expect(wrapper.find('[data-testid="cel-error-loc"]').text()).toMatch(/3\s*:\s*5/)
  })

  it('试运行展示 matched / decision', async () => {
    const orch = await import('@/api/orch')
    vi.mocked(orch.dryRunVersion).mockResolvedValueOnce({
      matched: true,
      decision: 'WOULD_ACCEPT',
      reason: 'observe_inferred',
      versionId: 'rv_fixture_canary',
      fellBackToStable: false
    })

    const wrapper = mount(RulesView, { global: { stubs } })
    await flushPromises()

    const vm = wrapper.vm as unknown as {
      openDryRun: (ruleId: string, versionId: string) => void
      runDryRun: () => Promise<void>
      dryRunResult: { matched: boolean; decision: string } | null
    }
    vm.openDryRun('rule_fixture_01', 'rv_fixture_canary')
    await nextTick()
    await vm.runDryRun()
    await flushPromises()

    expect(vm.dryRunResult?.matched).toBe(true)
    expect(vm.dryRunResult?.decision).toBe('WOULD_ACCEPT')
    expect(wrapper.find('[data-testid="dry-run-result"]').text()).toMatch(/WOULD_ACCEPT/)
  })

  it('观察模式审阅列出 WOULD_ACCEPT', async () => {
    const wrapper = mount(RulesView, { global: { stubs } })
    await flushPromises()

    const vm = wrapper.vm as unknown as {
      openObserve: (ruleId: string) => Promise<void>
      decisions: Array<{ decision: string }>
    }
    await vm.openObserve('rule_fixture_01')
    await flushPromises()

    expect(vm.decisions.every((d) => d.decision === 'WOULD_ACCEPT')).toBe(true)
    expect(vm.decisions.length).toBeGreaterThan(0)
    expect(wrapper.find('[data-testid="observe-review"]').exists()).toBe(true)
  })
})
