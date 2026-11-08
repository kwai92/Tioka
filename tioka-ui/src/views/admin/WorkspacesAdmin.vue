<template>
  <div class="page">
    <h1>空间与成员</h1>
    <el-alert v-if="msg" :title="msg" type="success" show-icon class="mb" @close="msg = ''" />
    <el-alert v-if="error" :title="error" type="error" show-icon class="mb" @close="error = ''" />

    <el-card shadow="never" class="mb">
      <el-form :inline="true" @submit.prevent>
        <el-form-item label="名称">
          <el-input v-model="newName" placeholder="新空间名称" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="busy" @click="onCreate">新建空间</el-button>
        </el-form-item>
      </el-form>
      <el-table :data="workspaces" size="small" stripe highlight-current-row @current-change="onPick">
        <el-table-column prop="id" label="ID" min-width="160" />
        <el-table-column prop="name" label="名称" width="180" />
        <el-table-column label="操作" width="100">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="onPick(row)">成员</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card v-if="current" shadow="never">
      <template #header>成员 · {{ current.name }} ({{ current.id }})</template>
      <el-form :inline="true" class="mb">
        <el-form-item label="用户 ID">
          <el-input v-model="memberUserId" />
        </el-form-item>
        <el-form-item label="角色">
          <el-input v-model="memberRole" placeholder="workspace_admin / member" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="busy" @click="onPutMember">添加/改角色</el-button>
        </el-form-item>
      </el-form>
      <el-table :data="members" size="small" stripe v-loading="busy">
        <el-table-column prop="userId" label="用户 ID" min-width="100" />
        <el-table-column prop="username" label="用户名" width="120" />
        <el-table-column prop="displayName" label="显示名" width="120" />
        <el-table-column prop="role" label="角色" width="140" />
        <el-table-column label="操作" width="80">
          <template #default="{ row }">
            <el-button link type="danger" size="small" @click="onRemove(row.userId)">移除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import {
  createWorkspace,
  listMembers,
  listWorkspaces,
  putMember,
  removeMember,
  type MemberView,
  type WorkspaceView
} from '@/admin/adminApi'

const workspaces = ref<WorkspaceView[]>([])
const members = ref<MemberView[]>([])
const current = ref<WorkspaceView | null>(null)
const newName = ref('')
const memberUserId = ref('')
const memberRole = ref('member')
const busy = ref(false)
const msg = ref('')
const error = ref('')

async function reloadWs() {
  busy.value = true
  error.value = ''
  try {
    workspaces.value = await listWorkspaces()
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    busy.value = false
  }
}

async function loadMembers() {
  if (!current.value) return
  busy.value = true
  try {
    const page = await listMembers(current.value.id)
    members.value = page.items
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    busy.value = false
  }
}

async function onCreate() {
  if (!newName.value.trim()) return
  busy.value = true
  try {
    await createWorkspace(newName.value.trim())
    newName.value = ''
    msg.value = '空间已创建'
    await reloadWs()
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    busy.value = false
  }
}

function onPick(row: WorkspaceView | null) {
  if (!row) return
  current.value = row
  void loadMembers()
}

async function onPutMember() {
  if (!current.value || !memberUserId.value.trim()) return
  await putMember(current.value.id, memberUserId.value.trim(), memberRole.value.trim() || 'member')
  msg.value = '成员已更新'
  memberUserId.value = ''
  await loadMembers()
}

async function onRemove(userId: string) {
  if (!current.value) return
  await removeMember(current.value.id, userId)
  msg.value = '已移除'
  await loadMembers()
}

onMounted(reloadWs)
</script>

<style scoped>
.mb { margin-bottom: 12px; }
</style>
