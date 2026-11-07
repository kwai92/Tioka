<template>
  <div class="page">
    <h1>服务账号与 API Key</h1>
    <p class="muted">按 FND-06 契约接线；后端未落地时创建会失败，明文弹窗关闭后仍会清空本地暂存。</p>
    <el-alert v-if="msg" :title="msg" type="success" show-icon class="mb" @close="msg = ''" />
    <el-alert v-if="error" :title="error" type="error" show-icon class="mb" @close="error = ''" />

    <el-card shadow="never" class="mb">
      <template #header>创建服务账号</template>
      <el-form :inline="true">
        <el-form-item label="名称"><el-input v-model="sa.name" /></el-form-item>
        <el-form-item label="空间"><el-input v-model="sa.workspaceId" /></el-form-item>
        <el-form-item label="角色"><el-input v-model="sa.role" placeholder="member" /></el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="busy" @click="onCreateSa">创建</el-button>
        </el-form-item>
      </el-form>
      <p v-if="lastSaId" class="muted">最近服务账号 userId：{{ lastSaId }}</p>
    </el-card>

    <el-card shadow="never" class="mb">
      <template #header>创建 API Key</template>
      <el-form :inline="true">
        <el-form-item label="服务账号">
          <el-input v-model="keyForm.serviceUserId" :placeholder="lastSaId || 'serviceUserId'" />
        </el-form-item>
        <el-form-item label="名称"><el-input v-model="keyForm.name" /></el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="busy" @click="onCreateKey">创建</el-button>
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
  listApiKeys
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
const keys = ref<Array<{ id: string; name: string; prefix: string; lastUsedAt: string | null }>>([])
const sa = reactive({
  name: '',
  workspaceId: auth.currentWorkspaceId || '',
  role: 'member'
})
const keyForm = reactive({ serviceUserId: '', name: 'default' })
const plaintext = ref<ApiKeyPlaintextState>(emptyApiKeyPlaintext())
const plainVisible = ref(false)

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
    const r = await createApiKey({
      serviceUserId: keyForm.serviceUserId || lastSaId.value,
      name: keyForm.name
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

onMounted(reloadKeys)
</script>

<style scoped>
.mb { margin-bottom: 12px; }
.mt { margin-top: 12px; }
.muted { color: var(--muted); font-size: 0.9rem; }
.mono { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; word-break: break-all; }
</style>
