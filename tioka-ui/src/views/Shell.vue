<template>
  <div class="shell">
    <aside>
      <div class="brand">Tioka</div>
      <WorkspaceSwitcher v-if="auth.workspaces.length" />
      <nav>
        <template v-for="section in appSections" :key="section.id">
          <div class="section-label">{{ section.label }}</div>
          <router-link v-for="item in section.items" :key="item.path" :to="item.path">
            {{ item.label }}
          </router-link>
        </template>
        <div class="section-label">管理</div>
        <router-link v-if="showAdmin" to="/admin">管理后台</router-link>
      </nav>
      <div v-if="auth.user" class="user">{{ auth.user.displayName || auth.user.username }}</div>
      <button class="logout" @click="logout">退出</button>
    </aside>
    <main><router-view /></main>
  </div>
</template>
<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { APP_NAV_SECTIONS, canSeeAdmin } from '@/admin/adminNav'
import WorkspaceSwitcher from '@/components/WorkspaceSwitcher.vue'

const router = useRouter()
const auth = useAuthStore()
const showAdmin = computed(() => canSeeAdmin(auth.permissions))
const appSections = APP_NAV_SECTIONS

async function logout() {
  await auth.logout()
  router.push('/login')
}
</script>
<style scoped>
.shell { display: grid; grid-template-columns: 220px 1fr; min-height: 100%; }
aside { background: color-mix(in srgb, var(--panel) 95%, black 5%); border-right: 1px solid #243041; padding: 20px 16px; display: flex; flex-direction: column; gap: 16px; }
.brand { font-weight: 700; font-size: 1.25rem; }
nav { display: grid; gap: 4px; flex: 1; overflow: auto; }
.section-label {
  margin-top: 10px;
  padding: 4px 10px;
  font-size: 0.7rem;
  letter-spacing: 0.04em;
  text-transform: uppercase;
  color: var(--muted);
}
.section-label:first-child { margin-top: 0; }
nav a { color: var(--muted); text-decoration: none; padding: 8px 10px; border-radius: 8px; }
nav a.router-link-active { background: #243447; color: var(--text); }
main { padding: 24px; min-height: 100%; }
.user { font-size: 0.85rem; color: var(--muted); }
.logout { border: 1px solid #334155; background: transparent; color: var(--muted); border-radius: 8px; padding: 8px; cursor: pointer; }
</style>
