import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useAuthStore } from '@/stores/auth'
import { router } from '@/router'
import { APP_NAV, CONNECT_ROUTES } from '@/admin/adminNav'

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

describe('PK-00d M2 route registry', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    const auth = useAuthStore()
    auth.accessToken = 'tka_test'
    auth.permissions = ['*']
  })

  it('路由 path 快照（含 M2 占位）', () => {
    const paths = router
      .getRoutes()
      .map((r) => r.path)
      .sort()
    expect(paths).toMatchSnapshot()
  })

  it('APP_NAV 与 CONNECT 登记的 path 均可解析', async () => {
    for (const item of APP_NAV) {
      await router.push(item.path)
      expect(router.currentRoute.value.matched.length).toBeGreaterThan(0)
    }
    for (const item of CONNECT_ROUTES) {
      await router.push(item.path)
      expect(router.currentRoute.value.path).toBe(item.path)
    }
  })

  it('/approval 重定向到 /approvals', async () => {
    await router.push('/approval')
    expect(router.currentRoute.value.path).toBe('/approvals')
  })
})
