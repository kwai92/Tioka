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

/** RT-04 事件流客户端（HCI-01 接入 fetchEventSource + 鉴权头）。 */
export function streamThread(threadId: string, handlers: StreamHandlers, signal: AbortSignal) {
  let lastId = handlers.lastEventId ?? ''
  return fetchEventSource(`/api/runtime/threads/${threadId}/events`, {
    headers: authHeaders(),
    signal,
    credentials: 'include',
    openWhenHidden: true,
    onmessage(ev) {
      if (ev.id) {
        lastId = ev.id
      }
      try {
        handlers.onEvent(JSON.parse(ev.data) as RuntimeEvent)
      } catch {
        /* ignore malformed */
      }
    },
    onerror() {
      /* 返回 undefined 让库按退避自动重连 */
    }
  })
}
