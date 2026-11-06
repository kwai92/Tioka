import { useAuthStore } from './stores/auth'

/** 兼容旧 import：统一走 api/http。 */
export { api, authHeaders } from './api/http'
export { streamThread } from './api/stream'
export type { RuntimeEvent, StreamHandlers } from './api/stream'
export { listConversationItems, startChatTurn, stopActiveTurn } from './api/chatAsync'

export type StreamPart = {
  type?: string
  text?: string
  payload?: Record<string, unknown>
  id?: string
  [key: string]: unknown
}

/**
 * @deprecated 使用 streamThread（RT-04）。过渡期仍支持旧 session SSE。
 */
export function openSessionStream(sessionId: string, onPart: (part: StreamPart) => void): EventSource {
  const auth = useAuthStore()
  const token = auth.accessToken || ''
  const url = `/api/runtime/sessions/${sessionId}/stream${token ? `?token=${encodeURIComponent(token)}` : ''}`
  const es = new EventSource(url)
  es.addEventListener('part', (ev) => {
    try {
      onPart(JSON.parse((ev as MessageEvent).data))
    } catch {
      /* ignore */
    }
  })
  return es
}
