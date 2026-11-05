import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useAuthStore } from '@/stores/auth'
import { __resetRefreshLatch, api } from '@/api/http'

describe('api http 401 refresh', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    __resetRefreshLatch()
    vi.restoreAllMocks()
    window.history.pushState({}, '', '/chat')
  })

  it('401 → 刷新 → 重试成功', async () => {
    const auth = useAuthStore()
    auth.accessToken = 'old'
    auth.currentWorkspaceId = 'ws1'
    auth.refreshSession = vi.fn().mockResolvedValue(true)

    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce({
        status: 401,
        json: async () => ({ code: 40100, message: 'unauthorized' })
      })
      .mockResolvedValueOnce({
        status: 200,
        json: async () => ({ code: 0, data: { ok: true } })
      })
    vi.stubGlobal('fetch', fetchMock)

    const data = await api<{ ok: boolean }>('/api/ping')
    expect(data.ok).toBe(true)
    expect(auth.refreshSession).toHaveBeenCalledTimes(1)
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })

  it('刷新失败 → 跳登录', async () => {
    const auth = useAuthStore()
    auth.accessToken = 'old'
    auth.refreshSession = vi.fn().mockResolvedValue(false)
    const assign = vi.fn()
    Object.defineProperty(window, 'location', {
      configurable: true,
      value: { pathname: '/chat', search: '', assign }
    })

    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        status: 401,
        json: async () => ({ code: 40100, message: 'unauthorized' })
      })
    )

    await expect(api('/api/ping')).rejects.toThrow(/未登录/)
    expect(assign).toHaveBeenCalledWith('/login?redirect=%2Fchat')
  })

  it('两个并发 401 只刷新一次', async () => {
    const auth = useAuthStore()
    auth.accessToken = 'old'
    let refreshCalls = 0
    auth.refreshSession = vi.fn().mockImplementation(async () => {
      refreshCalls += 1
      await new Promise((r) => setTimeout(r, 20))
      auth.accessToken = 'new'
      return true
    })

    let calls = 0
    vi.stubGlobal(
      'fetch',
      vi.fn().mockImplementation(async () => {
        calls += 1
        if (calls <= 2) {
          return { status: 401, json: async () => ({ code: 40100 }) }
        }
        return { status: 200, json: async () => ({ code: 0, data: 1 }) }
      })
    )

    await Promise.all([api('/api/a'), api('/api/b')])
    expect(refreshCalls).toBe(1)
  })
})
