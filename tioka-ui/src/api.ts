export async function api<T = unknown>(path: string, opts: RequestInit = {}): Promise<T> {
  const token = localStorage.getItem('tioka_token')
  const isForm = typeof FormData !== 'undefined' && opts.body instanceof FormData
  const headers: Record<string, string> = {
    ...(isForm ? {} : { 'Content-Type': 'application/json' }),
    ...(token ? { Authorization: `Bearer ${token}` } : {}),
    ...((opts.headers as Record<string, string>) || {})
  }
  const res = await fetch(path, { ...opts, headers })
  const body = await res.json()
  if (body.code !== 0) throw new Error(body.message || `请求失败 ${res.status}`)
  return body.data as T
}

export type StreamPart = {
  type?: string
  text?: string
  payload?: Record<string, unknown>
  id?: string
  [key: string]: unknown
}

export function openSessionStream(sessionId: string, onPart: (part: StreamPart) => void): EventSource {
  const token = localStorage.getItem('tioka_token') || ''
  const url = `/api/runtime/sessions/${sessionId}/stream${token ? `?token=${encodeURIComponent(token)}` : ''}`
  const es = new EventSource(url)
  es.addEventListener('part', (ev) => {
    try {
      onPart(JSON.parse((ev as MessageEvent).data))
    } catch {
      /* ignore malformed */
    }
  })
  es.onerror = () => {
    /* keep open; browser will retry */
  }
  return es
}
