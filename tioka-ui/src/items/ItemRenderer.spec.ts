import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { readdirSync, readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { execFileSync } from 'node:child_process'
import ItemRenderer from './ItemRenderer.vue'
import type { ItemFixtureSample } from './types'

vi.mock('../api', () => ({
  api: vi.fn().mockRejectedValue(new Error('offline'))
}))

const here = dirname(fileURLToPath(import.meta.url))
const fixturesDir = join(here, '__fixtures__')
const repoRoot = join(here, '../../..')

const elStubs = {
  'el-button': { template: '<button class="el-button-stub"><slot /></button>' },
  'el-input': {
    props: ['modelValue'],
    emits: ['update:modelValue'],
    template:
      '<textarea class="el-input-stub" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />'
  },
  'el-tag': { template: '<span class="el-tag-stub"><slot /></span>' },
  'el-drawer': { template: '<div class="el-drawer-stub"><slot /></div>' }
}

describe('HCI-04 fixtures sync', () => {
  it('runtime-event examples 与 __fixtures__ 同步', () => {
    execFileSync('node', ['scripts/gen-item-fixtures.mjs', '--check'], {
      cwd: repoRoot,
      stdio: 'pipe'
    })
  })
})

describe('HCI-04 ItemRenderer snapshots', () => {
  const files = readdirSync(fixturesDir)
    .filter((f) => f.endsWith('.json'))
    .sort()

  for (const file of files) {
    const samples = JSON.parse(readFileSync(join(fixturesDir, file), 'utf8')) as ItemFixtureSample[]
    for (const sample of samples) {
      it(`${file} / ${sample.name}`, () => {
        const wrapper = mount(ItemRenderer, {
          props: {
            item: sample.item,
            pairResult: sample.pair,
            approval: sample.approval ?? null
          },
          global: { stubs: elStubs }
        })
        expect(wrapper.html()).toMatchSnapshot()
      })
    }
  }
})
