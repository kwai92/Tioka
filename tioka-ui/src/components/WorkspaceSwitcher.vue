<template>
  <label class="ws">
    <span>空间</span>
    <select :value="auth.currentWorkspaceId ?? ''" @change="onChange">
      <option v-for="w in auth.workspaces" :key="w.id" :value="w.id">{{ w.name }}</option>
    </select>
  </label>
</template>

<script setup lang="ts">
import { useQueryClient } from '@tanstack/vue-query'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const router = useRouter()
const queryClient = useQueryClient()

function onChange(ev: Event) {
  const id = (ev.target as HTMLSelectElement).value
  if (!id || id === auth.currentWorkspaceId) return
  auth.setWorkspace(id)
  queryClient.clear()
  router.push('/chat')
}
</script>

<style scoped>
.ws { display: grid; gap: 4px; font-size: 0.8rem; color: var(--muted); }
select {
  border-radius: 8px; border: 1px solid #334155; background: #0b1220;
  color: var(--text); padding: 6px 8px; font: inherit;
}
</style>
