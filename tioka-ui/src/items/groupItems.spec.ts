import { describe, expect, it } from 'vitest'
import { groupItemsForRender } from './groupItems'

describe('HCI-04 groupItemsForRender', () => {
  it('按 callId 成对折叠 tool_call / tool_result', () => {
    const units = groupItemsForRender([
      {
        itemId: '1',
        seq: 1,
        itemType: 'message',
        content: { text: 'hi' }
      },
      {
        itemId: '2',
        seq: 2,
        itemType: 'tool_call',
        content: { callId: 'c1', name: 'echo', arguments: {} }
      },
      {
        itemId: '3',
        seq: 3,
        itemType: 'tool_result',
        content: { callId: 'c1', name: 'echo', ok: true, output: 'x' }
      }
    ])
    expect(units).toHaveLength(2)
    expect(units[0].kind).toBe('single')
    expect(units[1].kind).toBe('tool_pair')
    if (units[1].kind === 'tool_pair') {
      expect(units[1].call.itemId).toBe('2')
      expect(units[1].result?.itemId).toBe('3')
    }
  })
})
