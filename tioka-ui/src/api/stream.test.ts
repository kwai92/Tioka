import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { compareEventId, nextBackoffMs, streamThread } from './stream'
import {
  applyRuntimeEvent,
  createThreadState,
  insertOptimisticUser,
  sortedItems,
  type ThreadState
} from '@/chat/threadState'

function sseChunk(id: string, data: unknown): string {
  return `id: ${id}\ndata: ${JSON.stringify(data)}\n\n`
}

/** 可控 ReadableStream：按块推送 SSE，支持中途「断线」后第二次连接续传。 */
function mockFetchSequence(
  sessions: Array<{
    status?: number
    contentType?: string
    chunks: string[]
    /** 读完前关闭（模拟断线） */
    abortAfterChunks?: number
  }>
) {
  let call = 0
  const lastEventIds: Array<string | null> = []

  const fetchMock = vi.fn(async (_url: RequestInfo | URL, init?: RequestInit) => {
    const headers = new Headers(init?.headers as HeadersInit)
    lastEventIds.push(headers.get('Last-Event-ID') || headers.get('last-event-id'))
    const session = sessions[Math.min(call, sessions.length - 1)]
    call += 1

    let i = 0
    const limit = session.abortAfterChunks ?? session.chunks.length
    const stream = new ReadableStream<Uint8Array>({
      pull(controller) {
        if (i >= limit || i >= session.chunks.length) {
          controller.close()
          return
        }
        controller.enqueue(new TextEncoder().encode(session.chunks[i]))
        i += 1
      }
    })

    return new Response(stream, {
      status: session.status ?? 200,
      headers: {
        'Content-Type': session.contentType ?? 'text/event-stream'
      }
    })
  })

  return { fetchMock, lastEventIds, getCallCount: () => call }
}

describe('HCI-02 stream + resume', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.useRealTimers()
  })

  it('compareEventId：41.0 < 41.3 < 41 < 42.0', () => {
    expect(compareEventId('41.0', '41.3')).toBeLessThan(0)
    expect(compareEventId('41.3', '41')).toBeLessThan(0)
    expect(compareEventId('41', '42.0')).toBeLessThan(0)
  })

  it('退避 1s、2s、4s…最长 30s', () => {
    expect(nextBackoffMs(0)).toBe(1000)
    expect(nextBackoffMs(1)).toBe(2000)
    expect(nextBackoffMs(2)).toBe(4000)
    expect(nextBackoffMs(10)).toBe(30_000)
  })

  it('断线重连不重复文本：Last-Event-ID 续传 + 客户端去重', async () => {
    vi.useFakeTimers()
    const { fetchMock, lastEventIds } = mockFetchSequence([
      {
        chunks: [
          sseChunk('5.0', {
            type: 'item.created',
            item: { itemId: 'a', seq: 5, itemType: 'message', role: 'assistant', content: {} }
          }),
          sseChunk('5.1', { type: 'item.delta', delta: { itemId: 'a', index: 1, text: 'Hel' } }),
          sseChunk('5.2', { type: 'item.delta', delta: { itemId: 'a', index: 2, text: 'lo' } })
        ],
        abortAfterChunks: 3
      },
      {
        chunks: [
          // 服务端可能重发；客户端应按 id 去重
          sseChunk('5.1', { type: 'item.delta', delta: { itemId: 'a', index: 1, text: 'Hel' } }),
          sseChunk('5.2', { type: 'item.delta', delta: { itemId: 'a', index: 2, text: 'lo' } }),
          sseChunk('5.3', { type: 'item.delta', delta: { itemId: 'a', index: 3, text: '!' } }),
          sseChunk('5', {
            type: 'item.completed',
            item: {
              itemId: 'a',
              seq: 5,
              itemType: 'message',
              role: 'assistant',
              content: { text: 'Hello!' }
            }
          })
        ]
      }
    ])

    let state: ThreadState = createThreadState()
    const ac = new AbortController()
    const done = streamThread(
      'thr-1',
      {
        fetch: fetchMock as unknown as typeof fetch,
        onEvent(ev, id) {
          if (id && state.lastEventId && compareEventId(id, state.lastEventId) <= 0) return
          state = applyRuntimeEvent(state, ev, id)
        }
      },
      ac.signal
    )

    // 跑完第一段 + 退避重连
    await vi.advanceTimersByTimeAsync(50)
    await Promise.resolve()
    expect(state.items.get('a')?.content.text).toBe('Hello')

    await vi.advanceTimersByTimeAsync(1000)
    await Promise.resolve()
    await vi.advanceTimersByTimeAsync(50)
    await Promise.resolve()

    expect(lastEventIds[1]).toBe('5.2')
    expect(state.items.get('a')?.content.text).toBe('Hello!')
    ac.abort()
    await done.catch(() => undefined)
  })

  it('discarded 后 item 消失；乐观用户消息被替换不重复', () => {
    let s = createThreadState()
    s = insertOptimisticUser(s, 'cm-1', 'hi')
    s = applyRuntimeEvent(s, {
      type: 'item.completed',
      item: {
        itemId: 'u1',
        seq: 1,
        itemType: 'message',
        role: 'user',
        content: { text: 'hi' }
      }
    })
    s = applyRuntimeEvent(s, {
      type: 'item.completed',
      item: {
        itemId: 'a1',
        seq: 2,
        itemType: 'message',
        role: 'assistant',
        content: { text: 'old' }
      }
    })
    s = applyRuntimeEvent(s, {
      type: 'item.discarded',
      item: { itemId: 'a1', seq: 2, itemType: 'message' }
    })
    expect(sortedItems(s).map((i) => i.itemId)).toEqual(['u1'])
    expect(s.discardNotice).toMatch(/重新生成/)
  })
})
