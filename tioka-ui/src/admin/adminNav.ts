/** HCI-03：管理后台导航与权限过滤；P3-00c：M3 四分区导航登记（K7）。 */

export type AdminNavItem = {
  path: string
  label: string
  /** 任一命中即可显示；空数组表示登录即可（如 403 页不进菜单） */
  anyOf: string[]
}

/** 工作台主导航条目。 */
export type AppNavItem = {
  path: string
  label: string
}

/** 四分区导航段（不含 /admin，管理走 ADMIN_NAV）。 */
export type AppNavSection = {
  id: 'chat' | 'studio' | 'console'
  label: string
  items: AppNavItem[]
}

/**
 * 新页面同时认 `tenant:admin` 与 `system:admin`（FE-00 §1.5；改名另包）。
 */
export const PLATFORM_ADMIN = ['tenant:admin', 'system:admin', '*'] as const

export const ADMIN_NAV: AdminNavItem[] = [
  { path: '/admin/users', label: '用户', anyOf: [...PLATFORM_ADMIN] },
  {
    path: '/admin/workspaces',
    label: '空间与成员',
    anyOf: ['tenant:admin', 'system:admin', 'workspace:manage', '*']
  },
  { path: '/admin/roles', label: '角色', anyOf: [...PLATFORM_ADMIN] },
  { path: '/admin/groups', label: '组', anyOf: [...PLATFORM_ADMIN] },
  { path: '/admin/api-keys', label: '服务账号与 API Key', anyOf: [...PLATFORM_ADMIN] },
  {
    path: '/admin/audit',
    label: '审计',
    anyOf: ['audit:read', 'tenant:admin', 'system:admin', '*']
  },
  {
    path: '/admin/quota',
    label: '配额与成本',
    anyOf: ['quota:read', 'tenant:admin', 'system:admin', '*']
  },
  {
    path: '/admin/models',
    label: '模型中枢',
    anyOf: ['model:admin', 'tenant:admin', 'system:admin', '*']
  },
  {
    path: '/admin/data',
    label: '数据集与去向',
    anyOf: ['workspace:manage', 'tenant:admin', 'system:admin', '*']
  },
  {
    path: '/admin/plugins',
    label: '插件',
    anyOf: ['sys:plugin:admin', 'tenant:admin', 'system:admin', '*']
  },
  {
    path: '/admin/im/bindings',
    label: '飞书接入',
    anyOf: ['tenant:admin', 'system:admin', 'workspace:manage', '*']
  },
  {
    path: '/admin/connected-apps',
    label: '已连接的应用',
    anyOf: ['tenant:admin', 'system:admin', 'workspace:manage', '*']
  },
  {
    path: '/console/ops/dead-letters',
    label: '死信',
    anyOf: ['orch:operate', 'tenant:admin', 'system:admin', '*']
  },
  {
    path: '/console/ops/timers',
    label: '定时器',
    anyOf: ['orch:write', 'orch:operate', 'tenant:admin', 'system:admin', '*']
  }
]

/**
 * M3 工作台四分区导航（P3-00c / FE-00 §3）。
 * 后续 FE 包只实现 views/{area}，不改本表 path；可追加 label。
 */
export const APP_NAV_SECTIONS: AppNavSection[] = [
  {
    id: 'chat',
    label: '对话',
    items: [{ path: '/chat', label: '会话' }]
  },
  {
    id: 'studio',
    label: '构建',
    items: [
      { path: '/studio/agents', label: 'Agent' },
      { path: '/studio/prompts', label: 'Prompt' },
      { path: '/studio/flows', label: 'Flow' },
      { path: '/studio/knowledge', label: '知识' },
      { path: '/studio/mcp', label: 'MCP' },
      { path: '/studio/skills', label: 'Skill' }
    ]
  },
  {
    id: 'console',
    label: '运行',
    items: [
      { path: '/console/approvals', label: '审批' },
      { path: '/console/trajectory', label: '轨迹' },
      { path: '/console/timeline', label: '时间线' },
      { path: '/console/orch/rules', label: '编排规则' },
      { path: '/console/orch/work-items', label: '工单与 Run' },
      { path: '/console/ops/dead-letters', label: '死信' },
      { path: '/console/ops/timers', label: '定时器' },
      { path: '/console/models', label: '模型运行' },
      { path: '/console/flows/runs', label: 'Flow Run' },
      { path: '/console/eval', label: '评测' }
    ]
  }
]

/** 扁平列表：兼容旧测试与遍历。 */
export const APP_NAV: AppNavItem[] = APP_NAV_SECTIONS.flatMap((s) => s.items)

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
