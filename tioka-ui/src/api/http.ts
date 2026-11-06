import { useAuthStore } from '@/stores/auth'

export type ApiError = Error & { status?: number; code?: number }

let refreshPromise: Promise<boolean> | null = null

export function authHeaders(): Record<string, string> {
  const auth = useAuthStore()
  const headers: Record<string, string> = {}
  if (auth.accessToken) {
    headers.Authorization = `Bearer ${auth.accessToken}`
  }
  if (auth.currentWorkspaceId) {
    headers['X-Workspace-Id'] = auth.currentWorkspaceId
  }
  return headers
}

async function tryRefresh(): Promise<boolean> {
  if (!refreshPromise) {
    refreshPromise = (async () => {
      try {
        const auth = useAuthStore()
        return await auth.refreshSession()
      } finally {
        refreshPromise = null
      }
    })()
  }
  return refreshPromise
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
 * 统一 HTTP 客户端：自动带 Authorization / X-Workspace-Id；
 * 401 时刷新一次（并发共享 Promise），仍失败则跳登录。
 */
export async function api<T = unknown>(path: string, opts: RequestInit = {}): Promise<T> {
  const isForm = typeof FormData !== 'undefined' && opts.body instanceof FormData
  const doFetch = async () => {
    const headers: Record<string, string> = {
      ...(isForm ? {} : { 'Content-Type': 'application/json' }),
      ...authHeaders(),
      ...((opts.headers as Record<string, string>) || {})
    }
    return fetch(path, { ...opts, headers, credentials: 'include' })
  }

  let res = await doFetch()
  if (res.status === 401) {
    const ok = await tryRefresh()
    if (ok) {
      res = await doFetch()
    } else {
      redirectToLogin()
      const err: ApiError = new Error('未登录或令牌已过期')
      err.status = 401
      err.code = 40100
      throw err
    }
  }

  const body = await res.json().catch(() => ({ code: res.status, message: `请求失败 ${res.status}` }))
  if (body.code !== 0) {
    if (res.status === 401 || body.code === 40100) {
      redirectToLogin()
    }
    const err: ApiError = new Error(body.message || `请求失败 ${res.status}`)
    err.status = res.status
    err.code = body.code
    throw err
  }
  return body.data as T
}

/** 供单测注入：重置刷新 Promise。 */
export function __resetRefreshLatch() {
  refreshPromise = null
}
