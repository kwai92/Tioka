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
import Approval from '@/views/Approval.vue'
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

export const router = createRouter({
  history: import.meta.env.VITEST ? createMemoryHistory() : createWebHistory(),
  routes: [
    { path: '/login', component: Login, meta: { public: true } },
    { path: '/login/callback', component: LoginCallback, meta: { public: true } },
    { path: '/account/password', component: ChangePassword, meta: { auth: true } },
    {
      path: '/',
      component: Shell,
      meta: { auth: true },
      children: [
        { path: '', redirect: '/chat' },
        { path: 'chat', component: Chat },
        { path: 'models', component: Models },
        { path: 'agents', component: Agents },
        { path: 'mcp', component: Mcp },
        { path: 'approval', component: Approval },
        { path: 'observe', component: Observability },
        { path: 'knowledge', component: Knowledge },
        { path: 'flows', component: FlowStudio },
        { path: 'skills', component: Skills },
        {
          path: 'admin',
          component: AdminLayout,
          meta: { auth: true },
          children: [
            { path: '', redirect: '/admin/users' },
            { path: 'forbidden', component: Forbidden },
            {
              path: 'users',
              component: UsersAdmin,
              meta: { anyOf: ['tenant:admin', '*'] }
            },
            {
              path: 'workspaces',
              component: WorkspacesAdmin,
              meta: { anyOf: ['tenant:admin', 'workspace:manage', '*'] }
            },
            {
              path: 'roles',
              component: RolesAdmin,
              meta: { anyOf: ['tenant:admin', '*'] }
            },
            {
              path: 'groups',
              component: GroupsAdmin,
              meta: { anyOf: ['tenant:admin', '*'] }
            },
            {
              path: 'api-keys',
              component: ApiKeysAdmin,
              meta: { anyOf: ['tenant:admin', '*'] }
            },
            {
              path: 'audit',
              component: AuditAdmin,
              meta: { anyOf: ['audit:read', 'tenant:admin', '*'] }
            },
            {
              path: 'quota',
              component: QuotaAdmin,
              meta: { anyOf: ['quota:read', 'tenant:admin', '*'] }
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
  if (to.path.startsWith('/admin') && to.path !== '/admin/forbidden') {
    if (!canSeeAdmin(auth.permissions) && to.path === '/admin') {
      return { path: '/admin/forbidden' }
    }
    const anyOf = to.matched
      .map((r) => r.meta.anyOf as string[] | undefined)
      .filter(Boolean)
      .pop()
    if (anyOf && !routeAllowed(auth.permissions, anyOf)) {
      return { path: '/admin/forbidden' }
    }
  }
  return true
})

export default router
