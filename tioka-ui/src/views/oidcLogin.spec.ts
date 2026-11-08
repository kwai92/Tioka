import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useAuthStore } from '@/stores/auth'
import { router } from '@/router'
import {
  buildOidcAuthorizeUrl,
  DEFAULT_OIDC_IDP,
  normalizeOidcRedirect
} from './oidcLogin'

describe('FE-05 oidcLogin', () => {
  it('authorize URL 含 idp 与 redirect', () => {
    expect(buildOidcAuthorizeUrl({ idp: 'keycloak', redirect: '/chat' })).toBe(
      '/api/system/auth/oidc/keycloak/authorize?redirect=%2Fchat'
    )
    expect(DEFAULT_OIDC_IDP).toBe('keycloak')
  })

  it('redirect 安全归一化', () => {
    expect(normalizeOidcRedirect('/chat')).toBe('/chat')
    expect(normalizeOidcRedirect('/agents?x=1')).toBe('/agents?x=1')
    expect(normalizeOidcRedirect('')).toBe('/')
    expect(normalizeOidcRedirect(undefined)).toBe('/')
    expect(normalizeOidcRedirect('//evil')).toBe('/')
    expect(normalizeOidcRedirect('https://evil.example')).toBe('/')
    expect(normalizeOidcRedirect('chat')).toBe('/')
  })
})

describe('FE-05 router /login/callback', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('未登录可进入 /login/callback（公开路由）', async () => {
    const auth = useAuthStore()
    auth.accessToken = null
    await router.push({ path: '/login/callback', query: { redirect: '/chat' } })
    expect(router.currentRoute.value.path).toBe('/login/callback')
    expect(router.currentRoute.value.query.redirect).toBe('/chat')
  })
})
