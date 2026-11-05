<template>
  <div class="shell">
    <aside>
      <div class="brand">Tioka</div>
      <nav>
        <router-link to="/">会话</router-link>
        <router-link to="/models">模型</router-link>
        <router-link to="/agents">Agent</router-link>
        <router-link to="/mcp">MCP</router-link>
        <router-link to="/skills">Skill</router-link>
        <router-link to="/approval">审批</router-link>
        <router-link to="/observe">观测</router-link>
        <router-link to="/knowledge">知识</router-link>
        <router-link to="/flows">Flow</router-link>
      </nav>
      <button class="logout" @click="logout">退出</button>
    </aside>
    <main><router-view /></main>
  </div>
</template>
<script setup>
import { useRouter } from 'vue-router'
import { useSessionStore } from '../stores/session'
const router = useRouter()
const session = useSessionStore()
function logout() {
  session.clear()
  router.push('/login')
}
</script>
<style scoped>
.shell { display: grid; grid-template-columns: 220px 1fr; min-height: 100%; }
aside { background: color-mix(in srgb, var(--panel) 95%, black 5%); border-right: 1px solid #243041; padding: 20px 16px; display: flex; flex-direction: column; gap: 16px; }
.brand { font-weight: 700; font-size: 1.25rem; }
nav { display: grid; gap: 8px; flex: 1; }
nav a { color: var(--muted); text-decoration: none; padding: 8px 10px; border-radius: 8px; }
nav a.router-link-active { background: #243447; color: var(--text); }
main { padding: 24px; min-height: 100%; }
.logout { border: 1px solid #334155; background: transparent; color: var(--muted); border-radius: 8px; padding: 8px; cursor: pointer; }
</style>
