import { describe, expect, it } from 'vitest'
import { parseArgsJson, validateToolArgs } from './validateArgs'

describe('HCI-04 validateToolArgs', () => {
  const schema = {
    type: 'object',
    properties: { path: { type: 'string' }, body: { type: 'string' } },
    required: ['path', 'body'],
    additionalProperties: false
  }

  it('合法参数通过', () => {
    const r = validateToolArgs({ path: '/a', body: 'x' }, schema)
    expect(r.ok).toBe(true)
  })

  it('缺必填字段失败', () => {
    const r = validateToolArgs({ path: '/a' }, schema)
    expect(r.ok).toBe(false)
    if (!r.ok) expect(r.message.length).toBeGreaterThan(0)
  })

  it('无 schema 时只校验对象', () => {
    expect(validateToolArgs({ a: 1 }, null).ok).toBe(true)
    expect(validateToolArgs('x', null).ok).toBe(false)
  })

  it('parseArgsJson 拒绝非对象', () => {
    expect(parseArgsJson('[1]').ok).toBe(false)
    expect(parseArgsJson('{"a":1}').ok).toBe(true)
  })
})
