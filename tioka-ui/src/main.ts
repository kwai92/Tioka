import { createApp } from 'vue'
import { createPinia } from 'pinia'
import { VueQueryPlugin } from '@tanstack/vue-query'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import 'element-plus/theme-chalk/dark/css-vars.css'
import App from './App.vue'
import router from './router'
import { useAuthStore } from './stores/auth'

document.documentElement.classList.add('dark')

async function boot() {
  const app = createApp(App)
  const pinia = createPinia()
  app.use(pinia)
  app.use(VueQueryPlugin)
  app.use(router)
  app.use(ElementPlus)

  const auth = useAuthStore()
  await auth.refreshSession()

  app.mount('#app')
}

boot()
