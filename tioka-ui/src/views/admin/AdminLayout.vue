<template>
  <div class="admin">
    <aside class="subnav">
      <div class="title">管理后台</div>
      <nav>
        <router-link v-for="item in nav" :key="item.path" :to="item.path">{{ item.label }}</router-link>
      </nav>
    </aside>
    <section class="body">
      <router-view />
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useAuthStore } from '@/stores/auth'
import { filterAdminNav } from '@/admin/adminNav'

const auth = useAuthStore()
const nav = computed(() => filterAdminNav(auth.permissions))
</script>

<style scoped>
.admin { display: grid; grid-template-columns: 180px 1fr; gap: 16px; min-height: 60vh; }
.subnav {
  background: color-mix(in srgb, var(--panel) 90%, black 10%);
  border: 1px solid #243041;
  border-radius: 12px;
  padding: 14px;
  display: flex;
  flex-direction: column;
  gap: 10px;
  height: fit-content;
}
.title { font-weight: 600; font-size: 0.95rem; }
nav { display: grid; gap: 6px; }
nav a { color: var(--muted); text-decoration: none; padding: 6px 8px; border-radius: 8px; font-size: 0.9rem; }
nav a.router-link-active { background: #243447; color: var(--text); }
.body { min-width: 0; }
</style>
