<template>
  <div class="wrap">
    <div class="card">
      <div class="brand">Tioka</div>
      <p v-if="!error" class="sub">{{ status }}</p>
      <p v-else class="err">{{ error }}</p>
      <a v-if="error" class="back" :href="loginHref">返回登录</a>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { normalizeOidcRedirect } from './oidcLogin'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const status = ref('正在完成登录…')
const error = ref('')

const safeRedirect = computed(() =>
  normalizeOidcRedirect(typeof route.query.redirect === 'string' ? route.query.redirect : '/')
)

const loginHref = computed(() => {
  const r = safeRedirect.value
  return r === '/' ? '/login' : `/login?redirect=${encodeURIComponent(r)}`
})

onMounted(async () => {
  const ok = await auth.refreshSession()
  if (!ok) {
    error.value = '企业账号登录未完成：未取得会话，请重试或改用本地登录。'
    status.value = ''
    return
  }
  if (auth.user?.mustChangePassword) {
    await router.replace('/account/password')
    return
  }
  await router.replace(safeRedirect.value === '/' ? '/chat' : safeRedirect.value)
})
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
.sub { margin: 0; color: var(--muted); }
.err { color: #ff8e8e; margin: 0; }
.back { color: var(--accent); text-decoration: none; font-weight: 600; }
.back:hover { text-decoration: underline; }
</style>
