import { describe, expect, it } from 'vitest'
import {
  extractLegacyLlmDirectFields,
  filterPublishedRoutingPlans,
  hasLegacyLlmDirectFields,
  normalizeLlmNodeConfig
} from './flowLlmNode'

describe('FE-06 flowLlmNode', () => {
  it('识别 legacy 直连字段', () => {
    expect(hasLegacyLlmDirectFields({ baseUrl: 'http://x', model: 'gpt' })).toBe(true)
    expect(hasLegacyLlmDirectFields({ ApiKey: 'sk', modelName: 'm' })).toBe(true)
    expect(hasLegacyLlmDirectFields({ routingPlanId: 'rp_1' })).toBe(false)
    expect(hasLegacyLlmDirectFields({})).toBe(false)
    expect(hasLegacyLlmDirectFields(null)).toBe(false)
  })

  it('规范化写出剥离 legacy 并保留 routingPlanId', () => {
    expect(
      normalizeLlmNodeConfig({
        routingPlanId: 'rp_1',
        baseUrl: 'http://x',
        apiKey: 'sk',
        model: 'gpt',
        temperature: 0.2
      })
    ).toEqual({ routingPlanId: 'rp_1', temperature: 0.2 })
  })

  it('空 routingPlanId 剥离（FLOW 默认）', () => {
    expect(normalizeLlmNodeConfig({ routingPlanId: '' })).toEqual({})
    expect(normalizeLlmNodeConfig({ routingPlanId: '   ' })).toEqual({})
    expect(normalizeLlmNodeConfig({ routingPlanId: null })).toEqual({})
  })

  it('提取 legacy 只读字段', () => {
    expect(
      extractLegacyLlmDirectFields({ baseUrl: 'u', routingPlanId: 'rp', foo: 1 })
    ).toEqual({ baseUrl: 'u' })
  })

  it('下拉仅已发布方案', () => {
    expect(
      filterPublishedRoutingPlans([
        { id: 'a', name: 'A', published: true },
        { id: 'b', name: 'B', published: false },
        { id: 'c', name: 'C' }
      ]).map((p) => p.id)
    ).toEqual(['a'])
  })
})
