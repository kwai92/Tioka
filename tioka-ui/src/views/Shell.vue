<template>
  <div class="shell">
    <aside>
      <div class="brand">Tioka</div>
      <WorkspaceSwitcher v-if="auth.workspaces.length" />
      <nav>
        <router-link v-for="item in appNav" :key="item.path" :to="item.path">
          {{ item.label }}
        </router-link>
        <router-link v-if="showAdmin" to="/admin">管理后台</router-link>
      </nav>
      <div class="user" v-if="auth.user">{{ auth.user.displayName || auth.user.username }}</div>
      <button class="logout" @click="logout">退出</button>
    </aside>
    <main><router-view /></main>
  </div>
</template>
<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { APP_NAV, canSeeAdmin } from '@/admin/adminNav'
import WorkspaceSwitcher from '@/components/WorkspaceSwitcher.vue'

const router = useRouter()
const auth = useAuthStore()
const showAdmin = computed(() => canSeeAdmin(auth.permissions))
const appNav = APP_NAV

async function logout() {
  await auth.logout()
  router.push('/login')
}
</script>
<style scoped>
.shell { display: grid; grid-template-columns: 220px 1fr; min-height: 100%; }
aside { background: color-mix(in srgb, var(--panel) 95%, black 5%); border-right: 1px solid #243041; padding: 20px 16px; display: flex; flex-direction: column; gap: 16px; }
.brand { font-weight: 700; font-size: 1.25rem; }
nav { display: grid; gap: 8px; flex: 1; overflow: auto; }
nav a { color: var(--muted); text-decoration: none; padding: 8px 10px; border-radius: 8px; }
nav a.router-link-active { background: #243447; color: var(--text); }
main { padding: 24px; min-height: 100%; }
.user { font-size: 0.85rem; color: var(--muted); }
.logout { border: 1px solid #334155; background: transparent; color: var(--muted); border-radius: 8px; padding: 8px; cursor: pointer; }
</style>
