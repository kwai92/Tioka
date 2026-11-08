import { describe, expect, it, afterEach } from 'vitest'
import {
  registerDataItemRenderer,
  resolveDataItemRenderer,
  clearDataItemRenderers
} from './dataItemRegistry'

describe('SCH-04 data item registry (P3-00c)', () => {
  afterEach(() => {
    clearDataItemRenderers()
  })

  it('未注册 schema 返回 null', () => {
    expect(resolveDataItemRenderer('acme.invoice.v1')).toBeNull()
    expect(resolveDataItemRenderer(undefined)).toBeNull()
  })

  it('可按 schemaId 登记渲染器', () => {
    const stub = { name: 'InvoiceDataStub' }
    registerDataItemRenderer('acme.invoice.v1', stub)
    expect(resolveDataItemRenderer('acme.invoice.v1')).toBe(stub)
  })
})
