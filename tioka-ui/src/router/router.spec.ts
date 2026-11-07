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

  it('无 tenant:admin 直访 /admin/users → 403', async () => {
    const auth = useAuthStore()
    auth.accessToken = 'tka_test'
    auth.permissions = ['chat:use']
    await router.push('/admin/users')
    expect(router.currentRoute.value.path).toBe('/admin/forbidden')
  })

  it('有 audit:read 可进审计页', async () => {
    const auth = useAuthStore()
    auth.accessToken = 'tka_test'
    auth.permissions = ['audit:read']
    await router.push('/admin/audit')
    expect(router.currentRoute.value.path).toBe('/admin/audit')
  })
})
