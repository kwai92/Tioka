<template>
  <div class="wrap">
    <form class="card" @submit.prevent="onSubmit">
      <div class="brand">修改密码</div>
      <p class="sub">首次登录或管理员要求时需更新密码</p>
      <label>当前密码<input v-model="oldPassword" type="password" autocomplete="current-password" /></label>
      <label>新密码<input v-model="newPassword" type="password" autocomplete="new-password" /></label>
      <button type="submit" :disabled="loading">{{ loading ? '提交中…' : '保存' }}</button>
      <p v-if="error" class="err">{{ error }}</p>
    </form>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '@/api/http'

const router = useRouter()
const oldPassword = ref('')
const newPassword = ref('')
const loading = ref(false)
const error = ref('')

async function onSubmit() {
  loading.value = true
  error.value = ''
  try {
    await api('/api/system/auth/password', {
      method: 'POST',
      body: JSON.stringify({ oldPassword: oldPassword.value, newPassword: newPassword.value })
    })
    router.replace('/chat')
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.wrap { min-height: 100%; display: grid; place-items: center; padding: 24px; }
.card {
  width: min(400px, 100%);
  background: color-mix(in srgb, var(--panel) 92%, white 8%);
  border: 1px solid #2a3644;
  border-radius: 16px;
  padding: 28px;
  display: grid;
  gap: 12px;
}
.brand { font-size: 1.5rem; font-weight: 700; }
.sub { margin: 0 0 8px; color: var(--muted); }
label { display: grid; gap: 6px; font-size: 0.9rem; color: var(--muted); }
input, button {
  border-radius: 10px; border: 1px solid #334155; background: #0b1220;
  color: var(--text); padding: 10px 12px; font: inherit;
}
button { background: var(--accent); border-color: transparent; cursor: pointer; font-weight: 600; }
button:disabled { opacity: 0.6; cursor: wait; }
.err { color: #ff8e8e; margin: 0; }
</style>
