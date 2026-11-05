import { defineStore } from 'pinia'
import { ref, computed } from 'vue'

/** 登录会话（最小 Pinia store，供全局鉴权态读取） */
export const useSessionStore = defineStore('session', () => {
  const token = ref(localStorage.getItem('tioka_token') || '')
  const displayName = ref(localStorage.getItem('tioka_user') || '')

  const isLoggedIn = computed(() => !!token.value)

  function setSession(nextToken: string, name: string) {
    token.value = nextToken
    displayName.value = name
    localStorage.setItem('tioka_token', nextToken)
    localStorage.setItem('tioka_user', name)
  }

  function clear() {
    token.value = ''
    displayName.value = ''
    localStorage.removeItem('tioka_token')
    localStorage.removeItem('tioka_user')
  }

  return { token, displayName, isLoggedIn, setSession, clear }
})
