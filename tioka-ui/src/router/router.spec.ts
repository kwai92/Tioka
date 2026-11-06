import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useAuthStore } from '@/stores/auth'
import { router } from '@/router'

describe('router auth guard', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('未登录访问 /chat 跳 /login?redirect=/chat', async () => {
    const auth = useAuthStore()
    auth.accessToken = null
    await router.push('/chat')
    expect(router.currentRoute.value.path).toBe('/login')
    expect(router.currentRoute.value.query.redirect).toBe('/chat')
  })
})
