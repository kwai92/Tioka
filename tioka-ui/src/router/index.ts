import { createRouter, createWebHistory, createMemoryHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { canSeeAdmin, routeAllowed } from '@/admin/adminNav'
import Login from '@/views/Login.vue'
import LoginCallback from '@/views/LoginCallback.vue'
import ChangePassword from '@/views/ChangePassword.vue'
import Shell from '@/views/Shell.vue'
import Chat from '@/views/Chat.vue'
import Models from '@/views/Models.vue'
import Agents from '@/views/Agents.vue'
import Mcp from '@/views/Mcp.vue'
import Knowledge from '@/views/Knowledge.vue'
import FlowStudio from '@/views/FlowStudio.vue'
import Skills from '@/views/Skills.vue'
import Observability from '@/views/Observability.vue'
import AdminLayout from '@/views/admin/AdminLayout.vue'
import Forbidden from '@/views/admin/Forbidden.vue'
import UsersAdmin from '@/views/admin/UsersAdmin.vue'
import WorkspacesAdmin from '@/views/admin/WorkspacesAdmin.vue'
import RolesAdmin from '@/views/admin/RolesAdmin.vue'
import GroupsAdmin from '@/views/admin/GroupsAdmin.vue'
import ApiKeysAdmin from '@/views/admin/ApiKeysAdmin.vue'
import AuditAdmin from '@/views/admin/AuditAdmin.vue'
import QuotaAdmin from '@/views/admin/QuotaAdmin.vue'
import ApprovalDeskPlaceholder from '@/views/approval/ApprovalDeskPlaceholder.vue'
import TrajectoryPlaceholder from '@/views/trajectory/TrajectoryPlaceholder.vue'
import RulesPlaceholder from '@/views/orch/RulesPlaceholder.vue'
import WorkItemsPlaceholder from '@/views/orch/WorkItemsPlaceholder.vue'
import DeadLettersPlaceholder from '@/views/ops/DeadLettersPlaceholder.vue'
import TimersPlaceholder from '@/views/ops/TimersPlaceholder.vue'
import BindingsPlaceholder from '@/views/im/BindingsPlaceholder.vue'
import FeishuConnectPlaceholder from '@/views/connect/FeishuConnectPlaceholder.vue'
import ConnectedAppsPlaceholder from '@/views/connect/ConnectedAppsPlaceholder.vue'
import M3Placeholder from '@/views/m3/M3Placeholder.vue'

/**
 * P3-00c：四分区路由（FE-00 §3）+ 旧路径重定向。
 * 持有 K7；后续 FE 包只换 views，不改 path 登记。
 */
export const router = createRouter({
  history: import.meta.env.VITEST ? createMemoryHistory() : createWebHistory(),
  routes: [
    { path: '/login', component: Login, meta: { public: true } },
    { path: '/login/callback', component: LoginCallback, meta: { public: true } },
    { path: '/account/password', component: ChangePassword, meta: { auth: true } },
    {
      path: '/connect/feishu',
      component: FeishuConnectPlaceholder,
      meta: { auth: true }
    },
    { path: '/forbidden', component: Forbidden, meta: { auth: true } },

    /* —— 旧路径重定向（FE-00 §3） —— */
    { path: '/agents/:pathMatch(.*)*', redirect: (to) => `/studio/agents/${to.params.pathMatch || ''}`.replace(/\/$/, '') },
    { path: '/agents', redirect: '/studio/agents' },
    { path: '/mcp', redirect: '/studio/mcp' },
    { path: '/skills', redirect: '/studio/skills' },
    { path: '/knowledge/:pathMatch(.*)*', redirect: (to) => `/studio/knowledge/${to.params.pathMatch || ''}`.replace(/\/$/, '') },
    { path: '/knowledge', redirect: '/studio/knowledge' },
    { path: '/flows/:pathMatch(.*)*', redirect: (to) => `/studio/flows/${to.params.pathMatch || ''}`.replace(/\/$/, '') },
    { path: '/flows', redirect: '/studio/flows' },
    { path: '/models', redirect: '/admin/models' },
    { path: '/approvals', redirect: '/console/approvals' },
    { path: '/approval', redirect: '/console/approvals' },
    { path: '/trajectory/:turnId?', redirect: (to) => (to.params.turnId ? `/console/trajectory/${to.params.turnId}` : '/console/trajectory') },
    { path: '/orch/rules', redirect: '/console/orch/rules' },
    { path: '/orch/work-items/:id?', redirect: (to) => (to.params.id ? `/console/orch/work-items/${to.params.id}` : '/console/orch/work-items') },
    { path: '/ops/dead-letters', redirect: '/console/ops/dead-letters' },
    { path: '/ops/timers', redirect: '/console/ops/timers' },
    { path: '/im/bindings', redirect: '/admin/im/bindings' },
    { path: '/settings/connected-apps', redirect: '/admin/connected-apps' },
    { path: '/observe', redirect: '/console/models' },
    { path: '/admin/forbidden', redirect: '/forbidden' },

    {
      path: '/',
      component: Shell,
      meta: { auth: true },
      children: [
        { path: '', redirect: '/chat' },

        /* /chat */
        { path: 'chat', component: Chat },
        { path: 'chat/:threadId', component: Chat },

        /* /studio */
        { path: 'studio/agents', component: Agents, meta: { anyOf: ['agent:read', 'agent:write', '*'] } },
        { path: 'studio/agents/:id', component: Agents, meta: { anyOf: ['agent:read', 'agent:write', '*'] } },
        {
          path: 'studio/agents/:id/profile',
          component: M3Placeholder,
          meta: { title: '岗位档案', anyOf: ['agent:read', '*'] }
        },
        {
          path: 'studio/prompts',
          component: M3Placeholder,
          meta: { title: 'Prompt', anyOf: ['prompt:read', 'prompt:write', '*'] }
        },
        {
          path: 'studio/prompts/:id',
          component: M3Placeholder,
          meta: { title: 'Prompt 编辑', anyOf: ['prompt:read', 'prompt:write', '*'] }
        },
        { path: 'studio/flows', component: FlowStudio, meta: { anyOf: ['flow:read', 'flow:write', '*'] } },
        { path: 'studio/flows/:id', component: FlowStudio, meta: { anyOf: ['flow:read', 'flow:write', '*'] } },
        { path: 'studio/knowledge', component: Knowledge, meta: { anyOf: ['kb:read', 'kb:write', '*'] } },
        { path: 'studio/knowledge/:kbId', component: Knowledge, meta: { anyOf: ['kb:read', 'kb:write', '*'] } },
        { path: 'studio/mcp', component: Mcp, meta: { anyOf: ['mcp:write', '*'] } },
        { path: 'studio/skills', component: Skills, meta: { anyOf: ['skill:write', '*'] } },

        /* /console */
        { path: 'console/approvals', component: ApprovalDeskPlaceholder },
        {
          path: 'console/trajectory/:turnId?',
          component: TrajectoryPlaceholder,
          meta: { anyOf: ['trajectory:read', '*'] }
        },
        {
          path: 'console/timeline',
          component: M3Placeholder,
          meta: { title: '时间线', anyOf: ['timeline:read', '*'] }
        },
        {
          path: 'console/orch/rules',
          component: RulesPlaceholder,
          meta: { anyOf: ['orch:write', '*'] }
        },
        {
          path: 'console/orch/work-items/:id?',
          component: WorkItemsPlaceholder,
          meta: { anyOf: ['orch:operate', 'orch:write', '*'] }
        },
        {
          path: 'console/ops/dead-letters',
          component: DeadLettersPlaceholder,
          meta: { anyOf: ['orch:operate', 'tenant:admin', 'system:admin', '*'] }
        },
        {
          path: 'console/ops/timers',
          component: TimersPlaceholder,
          meta: { anyOf: ['orch:write', 'orch:operate', 'tenant:admin', 'system:admin', '*'] }
        },
        {
          path: 'console/models',
          component: Observability,
          meta: { title: '模型运行状态', anyOf: ['model:read', '*'] }
        },
        {
          path: 'console/flows/runs',
          component: M3Placeholder,
          meta: { title: 'Flow Run', anyOf: ['flow:read', '*'] }
        },
        {
          path: 'console/eval',
          component: M3Placeholder,
          meta: { title: '评测', anyOf: ['eval:read', 'eval:write', '*'] }
        },

        /* /admin */
        {
          path: 'admin',
          component: AdminLayout,
          meta: { auth: true },
          children: [
            { path: '', redirect: '/admin/users' },
            {
              path: 'users',
              component: UsersAdmin,
              meta: { anyOf: ['tenant:admin', 'system:admin', '*'] }
            },
            {
              path: 'workspaces',
              component: WorkspacesAdmin,
              meta: { anyOf: ['tenant:admin', 'system:admin', 'workspace:manage', '*'] }
            },
            {
              path: 'roles',
              component: RolesAdmin,
              meta: { anyOf: ['tenant:admin', 'system:admin', '*'] }
            },
            {
              path: 'groups',
              component: GroupsAdmin,
              meta: { anyOf: ['tenant:admin', 'system:admin', '*'] }
            },
            {
              path: 'api-keys',
              component: ApiKeysAdmin,
              meta: { anyOf: ['tenant:admin', 'system:admin', '*'] }
            },
            {
              path: 'audit',
              component: AuditAdmin,
              meta: { anyOf: ['audit:read', 'tenant:admin', 'system:admin', '*'] }
            },
            {
              path: 'quota',
              component: QuotaAdmin,
              meta: { anyOf: ['quota:read', 'tenant:admin', 'system:admin', '*'] }
            },
            {
              path: 'models',
              component: Models,
              meta: { anyOf: ['model:admin', 'tenant:admin', 'system:admin', '*'] }
            },
            {
              path: 'data',
              component: M3Placeholder,
              meta: { title: '数据集与去向', anyOf: ['workspace:manage', 'tenant:admin', 'system:admin', '*'] }
            },
            {
              path: 'plugins',
              component: M3Placeholder,
              meta: { title: '插件', anyOf: ['sys:plugin:admin', 'tenant:admin', 'system:admin', '*'] }
            },
            {
              path: 'im/bindings',
              component: BindingsPlaceholder,
              meta: { anyOf: ['tenant:admin', 'system:admin', 'workspace:manage', '*'] }
            },
            {
              path: 'connected-apps',
              component: ConnectedAppsPlaceholder,
              meta: { anyOf: ['tenant:admin', 'system:admin', 'workspace:manage', '*'] }
            }
          ]
        }
      ]
    }
  ]
})

router.beforeEach((to) => {
  const auth = useAuthStore()
  if (to.meta.public) return true
  if ((to.meta.auth || to.matched.some((r) => r.meta.auth)) && !auth.accessToken) {
    const redirect = to.redirectedFrom?.fullPath || to.fullPath
    return { path: '/login', query: { redirect } }
  }
  if (to.path === '/forbidden') return true
  if (to.path.startsWith('/admin')) {
    if (!canSeeAdmin(auth.permissions) && to.path === '/admin') {
      return { path: '/forbidden' }
    }
    const anyOf = to.matched
      .map((r) => r.meta.anyOf as string[] | undefined)
      .filter(Boolean)
      .pop()
    if (anyOf && !routeAllowed(auth.permissions, anyOf)) {
      return { path: '/forbidden' }
    }
  }
  return true
})

export default router
