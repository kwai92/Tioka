import { createRouter, createWebHistory, createMemoryHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import Login from '@/views/Login.vue'
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

export const router = createRouter({
  history: import.meta.env.VITEST ? createMemoryHistory() : createWebHistory(),
  routes: [
    { path: '/login', component: Login, meta: { public: true } },
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
        { path: 'skills', component: Skills }
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
  return true
})

export default router
