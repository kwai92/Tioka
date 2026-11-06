import { describe, expect, it, vi } from 'vitest'
import {
  createRoutingPlan,
  formatFailoverPreview,
  joinFallbackChain,
  parseFallbackChain,
  patchBinding,
  putDefaultPlan
} from './modelConsole'

describe('FE-03 modelConsole', () => {
  it('PATCH binding 调 API', async () => {
    const api = vi.fn().mockResolvedValue({
      id: 'bnd_1',
      enabled: false,
      inputPriceMicros: 1000
    })
    const out = await patchBinding(api, 'bnd_1', {
      enabled: false,
      inputPriceMicros: 1000
    })
    expect(out.enabled).toBe(false)
    expect(api).toHaveBeenCalledTimes(1)
    expect(api).toHaveBeenCalledWith('/api/model/bindings/bnd_1', {
      method: 'PATCH',
      body: JSON.stringify({ enabled: false, inputPriceMicros: 1000 })
    })
  })

  it('创建 plan 带 fallbackChain', async () => {
    const api = vi.fn().mockResolvedValue({
      id: 'rp_1',
      fallbackChain: 'bnd_b,bnd_c'
    })
    const chain = joinFallbackChain(['bnd_b', 'bnd_c'])
    await createRoutingPlan(api, {
      name: 'fb',
      type: 'PRIMARY_FALLBACK',
      primaryBindingId: 'bnd_a',
      fallbackChain: chain
    })
    expect(api).toHaveBeenCalledWith('/api/model/routing-plans', {
      method: 'POST',
      body: JSON.stringify({
        name: 'fb',
        type: 'PRIMARY_FALLBACK',
        primaryBindingId: 'bnd_a',
        fallbackChain: 'bnd_b,bnd_c'
      })
    })
    expect(parseFallbackChain('bnd_b,bnd_c')).toEqual(['bnd_b', 'bnd_c'])
    expect(formatFailoverPreview('bnd_a', ['bnd_b', 'bnd_c'])).toBe('bnd_a → bnd_b → bnd_c')
  })

  it('保存默认方案调 PUT', async () => {
    const api = vi.fn().mockResolvedValue({
      purpose: 'CHAT',
      routingPlanId: 'rp_1',
      bindingId: null
    })
    await putDefaultPlan(api, 'CHAT', { routingPlanId: 'rp_1' })
    expect(api).toHaveBeenCalledWith('/api/model/default-plans/CHAT', {
      method: 'PUT',
      body: JSON.stringify({ routingPlanId: 'rp_1', bindingId: null })
    })
  })
})
