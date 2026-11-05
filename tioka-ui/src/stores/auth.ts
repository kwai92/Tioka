import { defineStore } from 'pinia'
import { computed, ref } from 'vue'

export type AuthUser = {
  id: string
  username: string
  displayName: string
  mustChangePassword: boolean
}

export type Workspace = {
  id: string
  name: string
  role?: string
}

type LoginPayload = {
  accessToken: string
  user: AuthUser
  workspaces?: Workspace[]
}

const WS_KEY = 'tioka_workspace_id'

export const useAuthStore = defineStore('auth', () => {
  const accessToken = ref<string | null>(null)
  const user = ref<AuthUser | null>(null)
  const workspaces = ref<Workspace[]>([])
  const currentWorkspaceId = ref<string | null>(localStorage.getItem(WS_KEY))

  const isLoggedIn = computed(() => !!accessToken.value)

  function applyLogin(data: LoginPayload) {
    accessToken.value = data.accessToken
    user.value = data.user
    workspaces.value = data.workspaces ?? []
    if (!currentWorkspaceId.value && workspaces.value.length > 0) {
      setWorkspace(workspaces.value[0].id)
    } else if (
      currentWorkspaceId.value &&
      workspaces.value.length > 0 &&
      !workspaces.value.some((w) => w.id === currentWorkspaceId.value)
    ) {
      setWorkspace(workspaces.value[0].id)
    }
  }

  function setWorkspace(id: string) {
    currentWorkspaceId.value = id
    localStorage.setItem(WS_KEY, id)
  }

  function clear() {
    accessToken.value = null
    user.value = null
    workspaces.value = []
    // 保留 currentWorkspaceId 便于下次登录回同空间；仅清内存令牌
  }

  async function refreshSession(): Promise<boolean> {
    try {
      const res = await fetch('/api/system/auth/refresh', {
        method: 'POST',
        credentials: 'include'
      })
      const body = await res.json()
      if (body.code !== 0 || !body.data?.accessToken) {
        clear()
        return false
      }
      applyLogin(body.data)
      return true
    } catch {
      clear()
      return false
    }
  }

  async function login(username: string, password: string): Promise<LoginPayload> {
    const res = await fetch('/api/system/auth/login', {
      method: 'POST',
      credentials: 'include',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username, password })
    })
    const body = await res.json()
    if (body.code !== 0) {
      throw new Error(body.message || '登录失败')
    }
    applyLogin(body.data)
    return body.data
  }

  async function logout() {
    try {
      if (accessToken.value) {
        await fetch('/api/system/auth/logout', {
          method: 'POST',
          credentials: 'include',
          headers: { Authorization: `Bearer ${accessToken.value}` }
        })
      }
    } finally {
      clear()
      localStorage.removeItem(WS_KEY)
      currentWorkspaceId.value = null
    }
  }

  return {
    accessToken,
    user,
    workspaces,
    currentWorkspaceId,
    isLoggedIn,
    applyLogin,
    setWorkspace,
    clear,
    refreshSession,
    login,
    logout
  }
})
