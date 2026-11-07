import { describe, expect, it } from 'vitest'
import {
  APP_NAV,
  CONNECT_ROUTES,
  canSeeAdmin,
  filterAdminNav,
  hasAnyPermission,
  routeAllowed
} from './adminNav'
import { buildRolePermissionPayload, groupPermissionsByResource, togglePermission } from './roleMatrix'
import { clearApiKeyPlaintext, emptyApiKeyPlaintext, setApiKeyPlaintext } from './apiKeyPlaintext'
import { isOidcGroupSource, parseScopeList } from './adminApi'

describe('adminNav', () => {
  it('无 tenant:admin 时不显示用户/角色菜单', () => {
    const nav = filterAdminNav(['audit:read'])
    expect(nav.map((n) => n.path)).toEqual(['/admin/audit'])
    expect(canSeeAdmin(['chat:use'])).toBe(false)
    expect(canSeeAdmin(['audit:read'])).toBe(true)
  })

  it('* 可看全部管理菜单', () => {
    expect(filterAdminNav(['*']).length).toBeGreaterThan(5)
  })

  it('routeAllowed 校验 anyOf', () => {
    expect(routeAllowed(['quota:read'], ['quota:read', 'tenant:admin'])).toBe(true)
    expect(routeAllowed(['chat:use'], ['tenant:admin'])).toBe(false)
    expect(hasAnyPermission(['tenant:admin'], ['tenant:admin'])).toBe(true)
  })

  it('PK-00d 登记 M2 工作台与连接页导航', () => {
    const paths = APP_NAV.map((n) => n.path)
    expect(paths).toContain('/approvals')
    expect(paths).toContain('/trajectory')
    expect(paths).toContain('/orch/rules')
    expect(paths).toContain('/orch/work-items')
    expect(paths).toContain('/ops/dead-letters')
    expect(paths).toContain('/ops/timers')
    expect(paths).toContain('/im/bindings')
    expect(paths).toContain('/settings/connected-apps')
    expect(CONNECT_ROUTES.map((r) => r.path)).toEqual(['/connect/feishu'])
  })
})

describe('roleMatrix', () => {
  it('按资源分组且 payload 去重排序', () => {
    const groups = groupPermissionsByResource(['agent:write', 'agent:read', 'kb:read', 'chat:use'])
    expect(groups.map((g) => g.resource)).toEqual(['agent', 'chat', 'kb'])
    expect(groups[0].permissions).toEqual(['agent:read', 'agent:write'])
    expect(buildRolePermissionPayload(['kb:read', 'agent:read', 'kb:read'])).toEqual([
      'agent:read',
      'kb:read'
    ])
    const s = togglePermission(new Set(['a']), 'b', true)
    expect([...s].sort()).toEqual(['a', 'b'])
  })
})

describe('apiKeyPlaintext', () => {
  it('关闭弹窗后 store 明文被清除', () => {
    let state = emptyApiKeyPlaintext()
    state = setApiKeyPlaintext(state, 'k1', 'tk_live_abcdefghSECRET')
    expect(state.plaintext).toContain('tk_live_')
    state = clearApiKeyPlaintext(state)
    expect(state).toEqual({ keyId: null, plaintext: null })
  })
})

describe('adminApi helpers', () => {
  it('OIDC 组来源识别', () => {
    expect(isOidcGroupSource('oidc:keycloak')).toBe(true)
    expect(isOidcGroupSource('local')).toBe(false)
  })

  it('parseScopeList 去重 trim', () => {
    expect(parseScopeList(' agent:read, kb:read  agent:read ')).toEqual(['agent:read', 'kb:read'])
    expect(parseScopeList('')).toEqual([])
  })
})
