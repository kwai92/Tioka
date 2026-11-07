/** HCI-03：管理后台导航与权限过滤；PK-00d：M2 工作台导航登记（K7）。 */

export type AdminNavItem = {
  path: string
  label: string
  /** 任一命中即可显示；空数组表示登录即可（如 403 页不进菜单） */
  anyOf: string[]
}

/** 工作台主导航条目（含 M2 占位页）。 */
export type AppNavItem = {
  path: string
  label: string
}

export const ADMIN_NAV: AdminNavItem[] = [
  { path: '/admin/users', label: '用户', anyOf: ['tenant:admin', '*'] },
  { path: '/admin/workspaces', label: '空间与成员', anyOf: ['tenant:admin', 'workspace:manage', '*'] },
  { path: '/admin/roles', label: '角色', anyOf: ['tenant:admin', '*'] },
  { path: '/admin/groups', label: '组', anyOf: ['tenant:admin', '*'] },
  { path: '/admin/api-keys', label: '服务账号与 API Key', anyOf: ['tenant:admin', '*'] },
  { path: '/admin/audit', label: '审计', anyOf: ['audit:read', 'tenant:admin', '*'] },
  { path: '/admin/quota', label: '配额与成本', anyOf: ['quota:read', 'tenant:admin', '*'] },
  /** M2 运维入口（死信 / 定时） */
  { path: '/ops/dead-letters', label: '死信', anyOf: ['orch:operate', 'tenant:admin', '*'] },
  { path: '/ops/timers', label: '定时器', anyOf: ['orch:write', 'orch:operate', 'tenant:admin', '*'] },
  { path: '/im/bindings', label: '飞书接入', anyOf: ['tenant:admin', 'workspace:manage', '*'] }
]

/**
 * M2 工作台导航登记（PK-00d）。
 * 后续 FE 包只实现 views/{area}，不改本表顺序与 path。
 */
export const APP_NAV: AppNavItem[] = [
  { path: '/chat', label: '会话' },
  { path: '/models', label: '模型' },
  { path: '/agents', label: 'Agent' },
  { path: '/mcp', label: 'MCP' },
  { path: '/skills', label: 'Skill' },
  { path: '/approvals', label: '审批' },
  { path: '/trajectory', label: '轨迹' },
  { path: '/orch/rules', label: '编排规则' },
  { path: '/orch/work-items', label: '工单与 Run' },
  { path: '/ops/dead-letters', label: '死信' },
  { path: '/ops/timers', label: '定时器' },
  { path: '/im/bindings', label: '飞书接入' },
  { path: '/settings/connected-apps', label: '已连接的应用' },
  { path: '/observe', label: '观测' },
  { path: '/knowledge', label: '知识' },
  { path: '/flows', label: 'Flow' }
]

/** 公开入口：飞书账号绑定（不进主导航，由卡片链接进入）。 */
export const CONNECT_ROUTES = [{ path: '/connect/feishu', label: '绑定飞书账号' }] as const

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
