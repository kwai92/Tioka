/** HCI-03：管理后台导航与权限过滤。 */

export type AdminNavItem = {
  path: string
  label: string
  /** 任一命中即可显示；空数组表示登录即可（如 403 页不进菜单） */
  anyOf: string[]
}

export const ADMIN_NAV: AdminNavItem[] = [
  { path: '/admin/users', label: '用户', anyOf: ['tenant:admin', '*'] },
  { path: '/admin/workspaces', label: '空间与成员', anyOf: ['tenant:admin', 'workspace:manage', '*'] },
  { path: '/admin/roles', label: '角色', anyOf: ['tenant:admin', '*'] },
  { path: '/admin/groups', label: '组', anyOf: ['tenant:admin', '*'] },
  { path: '/admin/api-keys', label: '服务账号与 API Key', anyOf: ['tenant:admin', '*'] },
  { path: '/admin/audit', label: '审计', anyOf: ['audit:read', 'tenant:admin', '*'] },
  { path: '/admin/quota', label: '配额与成本', anyOf: ['quota:read', 'tenant:admin', '*'] }
]

export function hasAnyPermission(permissions: string[] | null | undefined, required: string[]): boolean {
  if (!required.length) return true
  const set = new Set(permissions ?? [])
  if (set.has('*')) return true
  return required.some((p) => set.has(p))
}

export function filterAdminNav(permissions: string[] | null | undefined): AdminNavItem[] {
  return ADMIN_NAV.filter((item) => hasAnyPermission(permissions, item.anyOf))
}

export function canSeeAdmin(permissions: string[] | null | undefined): boolean {
  return filterAdminNav(permissions).length > 0
}

/** 路由 meta.permission（anyOf）校验；无配置则放行。 */
export function routeAllowed(
  permissions: string[] | null | undefined,
  anyOf: string[] | undefined
): boolean {
  if (!anyOf || anyOf.length === 0) return true
  return hasAnyPermission(permissions, anyOf)
}
