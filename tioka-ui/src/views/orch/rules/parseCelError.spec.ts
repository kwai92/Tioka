import { describe, expect, it } from 'vitest'
import { parseCelError } from './parseCelError'

describe('FE-15 CEL 错误定位到行列', () => {
  it('解析编译失败的行列与消息', () => {
    const loc = parseCelError('CEL 编译失败（行列 2:7）: undeclared reference to event.foo')
    expect(loc).toEqual({
      line: 2,
      column: 7,
      message: 'undeclared reference to event.foo',
      raw: 'CEL 编译失败（行列 2:7）: undeclared reference to event.foo'
    })
  })

  it('解析禁止访问 body 的固定行列 1:1', () => {
    const loc = parseCelError('CEL 不得访问 event.body（行列 1:1）')
    expect(loc?.line).toBe(1)
    expect(loc?.column).toBe(1)
  })

  it('无行列信息时返回 null', () => {
    expect(parseCelError('网络错误')).toBeNull()
    expect(parseCelError('')).toBeNull()
    expect(parseCelError(null)).toBeNull()
  })
})
