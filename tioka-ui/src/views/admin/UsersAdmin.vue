<template>
  <div class="page">
    <h1>用户</h1>
    <el-alert v-if="msg" :title="msg" type="success" show-icon class="mb" @close="msg = ''" />
    <el-alert v-if="error" :title="error" type="error" show-icon class="mb" @close="error = ''" />

    <el-card shadow="never" class="mb">
      <el-form :inline="true" @submit.prevent>
        <el-form-item label="搜索">
          <el-input v-model="q" placeholder="用户名 / 显示名" clearable @keyup.enter="reload" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="reload">查询</el-button>
        </el-form-item>
      </el-form>
      <el-form :inline="true" :model="form" class="mb">
        <el-form-item label="用户名">
          <el-input v-model="form.username" />
        </el-form-item>
        <el-form-item label="显示名">
          <el-input v-model="form.displayName" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="busy" @click="onCreate">新建</el-button>
        </el-form-item>
      </el-form>
      <el-table :data="rows" size="small" stripe v-loading="busy">
        <el-table-column prop="id" label="ID" min-width="100" />
        <el-table-column prop="username" label="用户名" width="140" />
        <el-table-column prop="displayName" label="显示名" width="140" />
        <el-table-column prop="idp" label="来源" width="90" />
        <el-table-column label="状态" width="80">
          <template #default="{ row }">{{ row.enabled ? '启用' : '禁用' }}</template>
        </el-table-column>
        <el-table-column label="操作" min-width="220">
          <template #default="{ row }">
            <el-button v-if="row.enabled" link type="danger" size="small" @click="onDisable(row.id)">禁用</el-button>
            <el-button v-else link type="primary" size="small" @click="onEnable(row.id)">启用</el-button>
            <el-button link type="warning" size="small" @click="onReset(row.id)">重置密码</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="pwdVisible" title="初始密码（仅此一次）" width="420px" @closed="pwd = ''">
      <p class="mono">{{ pwd }}</p>
      <template #footer>
        <el-button type="primary" @click="copyPwd">复制并关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import {
  createUser,
  disableUser,
  enableUser,
  listUsers,
  resetUserPassword,
  type UserView
} from '@/admin/adminApi'

const rows = ref<UserView[]>([])
const q = ref('')
const busy = ref(false)
const msg = ref('')
const error = ref('')
const form = reactive({ username: '', displayName: '' })
const pwdVisible = ref(false)
const pwd = ref('')

async function reload() {
  busy.value = true
  error.value = ''
  try {
    const page = await listUsers(q.value.trim())
    rows.value = page.items
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    busy.value = false
  }
}

async function onCreate() {
  busy.value = true
  error.value = ''
  try {
    const created = await createUser({
      username: form.username.trim(),
      displayName: form.displayName.trim() || undefined
    })
    if (created.initialPassword) {
      pwd.value = created.initialPassword
      pwdVisible.value = true
    }
    msg.value = `已创建用户 ${created.id}`
    form.username = ''
    form.displayName = ''
    await reload()
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    busy.value = false
  }
}

async function onDisable(id: string) {
  await disableUser(id)
  msg.value = '已禁用'
  await reload()
}

async function onEnable(id: string) {
  await enableUser(id)
  msg.value = '已启用'
  await reload()
}

async function onReset(id: string) {
  const r = await resetUserPassword(id)
  pwd.value = r.initialPassword
  pwdVisible.value = true
}

async function copyPwd() {
  try {
    await navigator.clipboard.writeText(pwd.value)
  } catch {
    /* ignore */
  }
  pwdVisible.value = false
  pwd.value = ''
}

onMounted(reload)
</script>

<style scoped>
.mb { margin-bottom: 12px; }
.mono { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; word-break: break-all; }
</style>
