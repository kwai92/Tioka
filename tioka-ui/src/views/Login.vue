<template>
  <div class="wrap">
    <form class="card" @submit.prevent="onSubmit">
      <div class="brand">Tioka</div>
      <p class="sub">企业 Agent 套件 · 本地登录</p>
      <label>用户名<input v-model="username" autocomplete="username" /></label>
      <label>密码<input v-model="password" type="password" autocomplete="current-password" /></label>
      <button type="submit" :disabled="loading">{{ loading ? '登录中…' : '进入工作台' }}</button>
      <p v-if="error" class="err">{{ error }}</p>
    </form>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useSessionStore } from '../stores/session'

const router = useRouter()
const session = useSessionStore()
const username = ref('admin')
const password = ref('')
const loading = ref(false)
const error = ref('')

async function onSubmit() {
  loading.value = true
  error.value = ''
  try {
    const res = await fetch('/api/system/auth/login', {
      method: 'POST',
      credentials: 'include',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username: username.value, password: password.value })
    })
    const body = await res.json()
    if (body.code !== 0) throw new Error(body.message || '登录失败')
    const display = body.data.user?.displayName || body.data.user?.username || username.value
    session.setSession(body.data.accessToken, display)
    router.push('/')
  } catch (e) {
    error.value = e.message || String(e)
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
.brand { font-size: 2rem; font-weight: 700; letter-spacing: 0.04em; }
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
