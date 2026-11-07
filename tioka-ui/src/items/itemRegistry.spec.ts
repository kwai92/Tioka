import { describe, expect, it } from 'vitest'
import { resolveItemRenderer, registerItemRenderer, itemRenderers, UnknownItem } from './itemRegistry'
import MessageItem from './MessageItem.vue'

describe('F26 item registry', () => {
  it('已知类型走注册表', () => {
    expect(resolveItemRenderer('message')).toBe(MessageItem)
  })

  it('未知与未来类型兜底 UnknownItem', () => {
    expect(resolveItemRenderer('totally_unknown')).toBe(UnknownItem)
    expect(resolveItemRenderer('data')).toBe(UnknownItem)
    expect(resolveItemRenderer('change_set')).toBe(UnknownItem)
    expect(resolveItemRenderer(undefined)).toBe(UnknownItem)
  })

  it('可登记新类型而不改 ItemRenderer 布局', () => {
    const stub = { name: 'DataItemStub' }
    registerItemRenderer('data', stub)
    expect(resolveItemRenderer('data')).toBe(stub)
    delete itemRenderers.data
  })
})
