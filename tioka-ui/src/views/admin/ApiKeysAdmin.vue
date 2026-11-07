<template>
  <div class="page">
    <h1>服务账号与 API Key</h1>
    <p class="muted">
      服务账号加入指定空间并分配角色；API Key 明文仅创建时展示一次，关闭弹窗后本地暂存清空。
    </p>
    <el-alert v-if="msg" :title="msg" type="success" show-icon class="mb" @close="msg = ''" />
    <el-alert v-if="error" :title="error" type="error" show-icon class="mb" @close="error = ''" />

    <el-card shadow="never" class="mb">
      <template #header>创建服务账号</template>
      <el-form :inline="true">
        <el-form-item label="名称">
          <el-input v-model="sa.name" placeholder="显示名" />
        </el-form-item>
        <el-form-item label="空间">
          <el-select v-model="sa.workspaceId" filterable placeholder="选择空间" style="width: 220px">
            <el-option v-for="w in workspaces" :key="w.id" :label="`${w.name} (${w.id})`" :value="w.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="角色">
          <el-select v-model="sa.role" style="width: 140px">
            <el-option label="member" value="member" />
            <el-option label="builder" value="builder" />
            <el-option label="workspace_admin" value="workspace_admin" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="busy" :disabled="!sa.name || !sa.workspaceId" @click="onCreateSa">
            创建
          </el-button>
        </el-form-item>
      </el-form>
      <p v-if="lastSaId" class="muted">最近服务账号 userId：{{ lastSaId }}</p>
    </el-card>

    <el-card shadow="never" class="mb">
      <template #header>创建 API Key</template>
      <el-form :inline="true">
        <el-form-item label="服务账号">
          <el-input
            v-model="keyForm.serviceUserId"
            :placeholder="lastSaId || 'serviceUserId'"
            style="width: 180px"
          />
        </el-form-item>
        <el-form-item label="名称">
          <el-input v-model="keyForm.name" style="width: 140px" />
        </el-form-item>
        <el-form-item label="绑定空间">
          <el-select
            v-model="keyForm.workspaceId"
            clearable
            filterable
            placeholder="可选；绑定后忽略 X-Workspace-Id"
            style="width: 240px"
          >
            <el-option v-for="w in workspaces" :key="w.id" :label="`${w.name} (${w.id})`" :value="w.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="scopes">
          <el-input
            v-model="scopesText"
            placeholder="空=不收窄；逗号分隔权限点"
            style="width: 240px"
          />
        </el-form-item>
        <el-form-item label="过期">
          <el-date-picker v-model="expiresAt" type="datetime" clearable placeholder="可选" />
        </el-form-item>
        <el-form-item>
          <el-button
            type="primary"
            :loading="busy"
            :disabled="!(keyForm.serviceUserId || lastSaId) || !keyForm.name"
            @click="onCreateKey"
          >
            创建
          </el-button>
        </el-form-item>
      </el-form>
      <el-button size="small" @click="reloadKeys">刷新列表</el-button>
      <el-table :data="keys" size="small" stripe class="mt">
        <el-table-column prop="id" label="ID" min-width="120" />
        <el-table-column prop="name" label="名称" width="140" />
        <el-table-column prop="prefix" label="前缀" width="120" />
        <el-table-column prop="lastUsedAt" label="最近使用" min-width="160" />
        <el-table-column label="操作" width="80">
          <template #default="{ row }">
            <el-button link type="danger" size="small" @click="onDelete(row.id)">撤销</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog
      v-model="plainVisible"
      title="API Key 明文（仅此一次）"
      width="520px"
      @closed="onPlainClosed"
    >
      <p class="warn">请立即复制保存；关闭后无法再次查看。</p>
      <p class="mono">{{ plaintext.plaintext }}</p>
      <template #footer>
        <el-button type="primary" @click="copyPlain">复制并关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import {
  createApiKey,
  createServiceAccount,
  deleteApiKey,
  listApiKeys,
  listWorkspaces,
  parseScopeList,
  type WorkspaceView
} from '@/admin/adminApi'
import {
  clearApiKeyPlaintext,
  emptyApiKeyPlaintext,
  setApiKeyPlaintext,
  type ApiKeyPlaintextState
} from '@/admin/apiKeyPlaintext'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const busy = ref(false)
const msg = ref('')
const error = ref('')
const lastSaId = ref('')
const workspaces = ref<WorkspaceView[]>([])
const keys = ref<Array<{ id: string; name: string; prefix: string; lastUsedAt: string | null }>>([])
const sa = reactive({
  name: '',
  workspaceId: auth.currentWorkspaceId || '',
  role: 'member'
})
const keyForm = reactive({
  serviceUserId: '',
  name: 'default',
  workspaceId: '' as string
})
const scopesText = ref('')
const expiresAt = ref<Date | null>(null)
const plaintext = ref<ApiKeyPlaintextState>(emptyApiKeyPlaintext())
const plainVisible = ref(false)

async function loadWorkspaces() {
  try {
    workspaces.value = await listWorkspaces()
    if (!sa.workspaceId && workspaces.value.length) {
      sa.workspaceId = workspaces.value[0].id
    }
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : String(e)
    workspaces.value = []
  }
}

async function reloadKeys() {
  try {
    keys.value = await listApiKeys()
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : String(e)
    keys.value = []
  }
}

async function onCreateSa() {
  busy.value = true
  error.value = ''
  try {
    const r = await createServiceAccount({ ...sa })
    lastSaId.value = r.userId
    keyForm.serviceUserId = r.userId
    msg.value = '服务账号已创建'
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    busy.value = false
  }
}

async function onCreateKey() {
  busy.value = true
  error.value = ''
  try {
    const scopes = parseScopeList(scopesText.value)
    const r = await createApiKey({
      serviceUserId: keyForm.serviceUserId || lastSaId.value,
      name: keyForm.name,
      workspaceId: keyForm.workspaceId || undefined,
      scopes: scopes.length ? scopes : undefined,
      expiresAt: expiresAt.value ? expiresAt.value.toISOString() : undefined
    })
    plaintext.value = setApiKeyPlaintext(plaintext.value, r.id, r.key)
    plainVisible.value = true
    msg.value = 'API Key 已创建'
    await reloadKeys()
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    busy.value = false
  }
}

function onPlainClosed() {
  plaintext.value = clearApiKeyPlaintext(plaintext.value)
}

async function copyPlain() {
  if (plaintext.value.plaintext) {
    try {
      await navigator.clipboard.writeText(plaintext.value.plaintext)
    } catch {
      /* ignore */
    }
  }
  plainVisible.value = false
}

async function onDelete(id: string) {
  await deleteApiKey(id)
  msg.value = '已撤销'
  await reloadKeys()
}

onMounted(async () => {
  await loadWorkspaces()
  await reloadKeys()
})
</script>

<style scoped>
.mb { margin-bottom: 12px; }
.mt { margin-top: 12px; }
.muted { color: var(--muted); font-size: 0.9rem; }
.mono { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; word-break: break-all; }
.warn { color: var(--el-color-warning); margin-bottom: 8px; font-size: 0.9rem; }
</style>
