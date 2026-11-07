<template>
  <div class="wrap">
    <form class="card" @submit.prevent="onSubmit">
      <div class="brand">Tioka</div>
      <p class="sub">企业 Agent 套件 · 本地登录</p>
      <label>用户名<input v-model="username" autocomplete="username" /></label>
      <label>密码<input v-model="password" type="password" autocomplete="current-password" /></label>
      <button type="submit" :disabled="loading">{{ loading ? '登录中…' : '进入工作台' }}</button>
      <div class="divider" role="separator"><span>或</span></div>
      <button type="button" class="oidc" :disabled="loading" @click="onEnterpriseLogin">
        企业账号登录
      </button>
      <p v-if="error" class="err">{{ error }}</p>
    </form>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { buildOidcAuthorizeUrl, normalizeOidcRedirect } from './oidcLogin'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()
const username = ref('admin')
const password = ref('')
const loading = ref(false)
const error = ref('')

function loginRedirect(): string {
  return normalizeOidcRedirect(
    typeof route.query.redirect === 'string' ? route.query.redirect : '/chat'
  )
}

async function onSubmit() {
  loading.value = true
  error.value = ''
  try {
    const data = await auth.login(username.value, password.value)
    if (data.user?.mustChangePassword) {
      await router.replace('/account/password')
      return
    }
    const redirect = loginRedirect()
    await router.replace(redirect === '/' ? '/chat' : redirect)
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
}

function onEnterpriseLogin() {
  const url = buildOidcAuthorizeUrl({ redirect: loginRedirect() })
  window.location.assign(url)
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
button.oidc {
  background: transparent;
  border-color: #475569;
  color: var(--text);
}
button.oidc:hover:not(:disabled) { border-color: var(--accent); color: var(--accent); }
.divider {
  display: grid;
  grid-template-columns: 1fr auto 1fr;
  align-items: center;
  gap: 10px;
  color: var(--muted);
  font-size: 0.85rem;
}
.divider::before, .divider::after {
  content: '';
  height: 1px;
  background: #2a3644;
}
.err { color: #ff8e8e; margin: 0; }
</style>
