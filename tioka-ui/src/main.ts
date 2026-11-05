import { createApp } from 'vue'
import { createPinia } from 'pinia'
import { createRouter, createWebHistory } from 'vue-router'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import 'element-plus/theme-chalk/dark/css-vars.css'
import App from './App.vue'
import Login from './views/Login.vue'
import Shell from './views/Shell.vue'
import Chat from './views/Chat.vue'
import Models from './views/Models.vue'
import Agents from './views/Agents.vue'
import Mcp from './views/Mcp.vue'
import Approval from './views/Approval.vue'
import Knowledge from './views/Knowledge.vue'
import FlowStudio from './views/FlowStudio.vue'
import Skills from './views/Skills.vue'
import Observability from './views/Observability.vue'

document.documentElement.classList.add('dark')

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', component: Login },
    {
      path: '/',
      component: Shell,
      meta: { auth: true },
      children: [
        { path: '', component: Chat },
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
  if (to.meta.auth && !localStorage.getItem('tioka_token')) return '/login'
})

createApp(App).use(createPinia()).use(router).use(ElementPlus).mount('#app')
