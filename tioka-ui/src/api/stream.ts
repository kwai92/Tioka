import { fetchEventSource } from '@microsoft/fetch-event-source'
import { authHeaders } from './http'
import { useAuthStore } from '@/stores/auth'

export type RuntimeEvent = {
  type?: string
  [key: string]: unknown
}

export type StreamConnectionState = 'open' | 'reconnecting' | 'closed'

export type StreamHandlers = {
  lastEventId?: string
  onEvent: (ev: RuntimeEvent, eventId?: string) => void
  onConnection?: (state: StreamConnectionState) => void
  /** 可注入 fetch，便于 ReadableStream 单测 */
  fetch?: typeof fetch
}

const MAX_BACKOFF_MS = 30_000

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

/** 退避：1s、2s、4s…最长 30s。 */
export function nextBackoffMs(attempt: number): number {
  const ms = 1000 * Math.pow(2, Math.max(0, attempt))
  return Math.min(ms, MAX_BACKOFF_MS)
}

class FatalStreamError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'FatalStreamError'
  }
}

async function tryRefreshToken(): Promise<boolean> {
  try {
    const auth = useAuthStore()
    return await auth.refreshSession()
  } catch {
    return false
  }
}

function redirectToLogin() {
  const auth = useAuthStore()
  auth.clear()
  const redirect = encodeURIComponent(window.location.pathname + window.location.search)
  if (!window.location.pathname.startsWith('/login')) {
    window.location.assign(`/login?redirect=${redirect}`)
  }
}

/**
 * RT-04 / HCI-02 事件流客户端：
 * - openWhenHidden: true
 * - Last-Event-ID 续传 + after 查询参数（首连）
 * - 断线退避重连；401 先刷新令牌
 */
export function streamThread(threadId: string, handlers: StreamHandlers, signal: AbortSignal) {
  let lastId = handlers.lastEventId ?? ''
  let attempt = 0

  const qs = lastId ? `?after=${encodeURIComponent(lastId)}` : ''
  const url = `/api/runtime/threads/${threadId}/events${qs}`

  const baseHeaders: Record<string, string> = {
    Accept: 'text/event-stream'
  }
  if (lastId) {
    baseHeaders['Last-Event-ID'] = lastId
  }

  const doFetch: typeof fetch = async (input, init) => {
    const incoming =
      init?.headers instanceof Headers
        ? Object.fromEntries(init.headers.entries())
        : { ...((init?.headers as Record<string, string>) || {}) }
    const merged: Record<string, string> = {
      ...baseHeaders,
      ...incoming,
      ...authHeaders(),
      Accept: 'text/event-stream'
    }
    // 库写入的 last-event-id 优先；否则用本地 lastId
    const fromLib = incoming['last-event-id'] || incoming['Last-Event-ID']
    if (fromLib) {
      merged['Last-Event-ID'] = fromLib
      lastId = fromLib
    } else if (lastId) {
      merged['Last-Event-ID'] = lastId
    }
    const f = handlers.fetch ?? fetch
    return f(input, { ...init, headers: merged, credentials: 'include' })
  }

  return fetchEventSource(url, {
    headers: baseHeaders,
    signal,
    credentials: 'include',
    openWhenHidden: true,
    fetch: doFetch,
    async onopen(response) {
      if (response.status === 401) {
        const ok = await tryRefreshToken()
        if (!ok) {
          redirectToLogin()
          throw new FatalStreamError('未登录或令牌已过期')
        }
        throw new Error('token refreshed, reconnect')
      }
      if (!response.ok) {
        throw new Error(`SSE HTTP ${response.status}`)
      }
      const ct = response.headers.get('content-type') || ''
      if (!ct.includes('text/event-stream')) {
        throw new Error(`Expected text/event-stream, got ${ct}`)
      }
      attempt = 0
      handlers.onConnection?.('open')
    },
    onmessage(ev) {
      if (ev.id) {
        if (lastId && compareEventId(ev.id, lastId) <= 0) return
        lastId = ev.id
      }
      try {
        handlers.onEvent(JSON.parse(ev.data) as RuntimeEvent, ev.id || undefined)
      } catch {
        /* ignore malformed */
      }
    },
    onclose() {
      // sse-timeout 等正常关闭 → 触发重连
      throw new Error('SSE closed by server')
    },
    onerror(err) {
      if (err instanceof FatalStreamError || signal.aborted) {
        handlers.onConnection?.('closed')
        throw err
      }
      handlers.onConnection?.('reconnecting')
      const delay = nextBackoffMs(attempt)
      attempt += 1
      return delay
    }
  })
}
