import { fetchEventSource } from '@microsoft/fetch-event-source'
import { authHeaders } from './http'

export type RuntimeEvent = {
  type?: string
  [key: string]: unknown
}

export type StreamHandlers = {
  lastEventId?: string
  onEvent: (ev: RuntimeEvent) => void
}

/** 比较 SSE 事件 ID：先比 seq；同 seq 时有小数 < 无小数。 */
export function compareEventId(a: string, b: string): number {
  const pa = parseEventId(a)
  const pb = parseEventId(b)
  if (pa.seq !== pb.seq) return pa.seq < pb.seq ? -1 : 1
  if (pa.delta === null && pb.delta === null) return 0
  if (pa.delta === null) return 1
  if (pb.delta === null) return -1
  return pa.delta === pb.delta ? 0 : pa.delta < pb.delta ? -1 : 1
}

function parseEventId(raw: string): { seq: number; delta: number | null } {
  if (!raw) return { seq: 0, delta: null }
  const i = raw.indexOf('.')
  if (i < 0) return { seq: Number(raw), delta: null }
  return { seq: Number(raw.slice(0, i)), delta: Number(raw.slice(i + 1)) }
}

/** RT-04 事件流客户端。 */
export function streamThread(threadId: string, handlers: StreamHandlers, signal: AbortSignal) {
  let lastId = handlers.lastEventId ?? ''
  return fetchEventSource(`/api/runtime/threads/${threadId}/events`, {
    headers: authHeaders(),
    signal,
    credentials: 'include',
    openWhenHidden: true,
    onmessage(ev) {
      if (ev.id) {
        if (compareEventId(ev.id, lastId) <= 0) return
        lastId = ev.id
      }
      try {
        handlers.onEvent(JSON.parse(ev.data) as RuntimeEvent)
      } catch {
        /* ignore malformed */
      }
    },
    onerror() {
      /* 返回 undefined 让库按退避自动重连；重连时库会带 Last-Event-ID */
    }
  })
}
