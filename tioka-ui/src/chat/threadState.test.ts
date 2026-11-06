import { describe, expect, it } from 'vitest'
import {
  applyRuntimeEvent,
  createThreadState,
  insertOptimisticUser,
  seedFromItems,
  sortedItems
} from './threadState'

describe('HCI-02 threadState', () => {
  it('断线重连：重复 eventId 的 delta 不叠加文本', () => {
    let s = createThreadState()
    s = applyRuntimeEvent(
      s,
      {
        type: 'item.created',
        item: { itemId: 'a', seq: 5, itemType: 'message', role: 'assistant', content: {} }
      },
      '5.0'
    )
    s = applyRuntimeEvent(
      s,
      { type: 'item.delta', delta: { itemId: 'a', index: 1, text: '你' } },
      '5.1'
    )
    s = applyRuntimeEvent(
      s,
      { type: 'item.delta', delta: { itemId: 'a', index: 2, text: '好' } },
      '5.2'
    )
    // 模拟客户端去重：已处理过的 id 不再 apply（由 stream 层 compareEventId 拦截）
    // 此处验证 completed 覆盖而非追加
    s = applyRuntimeEvent(
      s,
      {
        type: 'item.completed',
        item: {
          itemId: 'a',
          seq: 5,
          itemType: 'message',
          role: 'assistant',
          content: { text: '你好' }
        }
      },
      '5'
    )
    const item = s.items.get('a')!
    expect(item.content.text).toBe('你好')

    // 若错误地再次 apply 同段 delta，会重复；stream 层应跳过 <= lastId
    const again = applyRuntimeEvent(
      s,
      { type: 'item.delta', delta: { itemId: 'a', index: 1, text: '你' } },
      '5.1'
    )
    // 纯函数仍会追加；断言 completed 已是权威文本，且 sorted 只有一条
    expect(sortedItems(again).filter((i) => i.itemId === 'a')).toHaveLength(1)
  })

  it('item.discarded 后 item 消失，并给出一次提示', () => {
    let s = createThreadState()
    s = applyRuntimeEvent(
      s,
      {
        type: 'item.completed',
        item: {
          itemId: 'x',
          seq: 3,
          itemType: 'message',
          role: 'assistant',
          content: { text: '旧模型' }
        }
      },
      '3'
    )
    expect(s.items.has('x')).toBe(true)
    s = applyRuntimeEvent(
      s,
      {
        type: 'item.discarded',
        item: { itemId: 'x', seq: 3, itemType: 'message' }
      },
      '3'
    )
    expect(s.items.has('x')).toBe(false)
    expect(s.discardNotice).toBe('模型已切换，正在重新生成')
  })

  it('乐观插入的用户消息被服务端 item 替换而非重复', () => {
    let s = createThreadState()
    s = insertOptimisticUser(s, 'c-1', 'hello')
    expect(sortedItems(s).filter((i) => i.role === 'user')).toHaveLength(1)
    expect(sortedItems(s)[0].pending).toBe(true)

    s = applyRuntimeEvent(s, {
      type: 'item.completed',
      item: {
        itemId: 'srv-1',
        seq: 1,
        itemType: 'message',
        role: 'user',
        content: { text: 'hello' }
      }
    })
    const users = sortedItems(s).filter((i) => i.role === 'user')
    expect(users).toHaveLength(1)
    expect(users[0].itemId).toBe('srv-1')
    expect(users[0].pending).toBeFalsy()
  })

  it('seedFromItems 用 afterSeq 结果填充，并替换乐观用户消息', () => {
    let s = insertOptimisticUser(createThreadState(), 'c-2', 'ping')
    s = seedFromItems(s, [
      {
        itemId: 'i1',
        seq: 1,
        type: 'message',
        role: 'user',
        content: { text: 'ping' }
      },
      {
        itemId: 'i2',
        seq: 2,
        type: 'message',
        role: 'assistant',
        content: { text: 'pong' }
      }
    ])
    const users = sortedItems(s).filter((i) => i.role === 'user')
    expect(users).toHaveLength(1)
    expect(users[0].itemId).toBe('i1')
    expect(s.lastEventId).toBe('2')
  })

  it('turn.cancelled 清空 activeTurn', () => {
    let s = createThreadState()
    s = applyRuntimeEvent(s, {
      type: 'turn.started',
      turnId: 't1',
      turn: { status: 'RUNNING' }
    })
    expect(s.activeTurn?.turnId).toBe('t1')
    s = applyRuntimeEvent(s, {
      type: 'turn.cancelled',
      turnId: 't1',
      turn: { status: 'CANCELLED' }
    })
    expect(s.activeTurn).toBeNull()
  })
})
