<template>
  <div class="page">
    <h1>组</h1>
    <el-alert v-if="msg" :title="msg" type="success" show-icon class="mb" @close="msg = ''" />
    <el-alert v-if="error" :title="error" type="error" show-icon class="mb" @close="error = ''" />

    <el-card shadow="never" class="mb">
      <el-form :inline="true">
        <el-form-item label="名称">
          <el-input v-model="newName" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="busy" @click="onCreate">新建本地组</el-button>
        </el-form-item>
      </el-form>
      <el-table :data="groups" size="small" stripe>
        <el-table-column prop="id" label="ID" min-width="140" />
        <el-table-column prop="name" label="名称" width="160" />
        <el-table-column label="来源" width="160">
          <template #default="{ row }">
            {{ row.source }}
            <el-tag v-if="isOidcGroupSource(row.source)" size="small" type="info" class="ml">OIDC 只读</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="成员数" width="90">
          <template #default="{ row }">{{ row.memberIds?.length ?? 0 }}</template>
        </el-table-column>
        <el-table-column label="操作" min-width="220">
          <template #default="{ row }">
            <template v-if="!isOidcGroupSource(row.source)">
              <el-input
                v-model="memberDraft[row.id]"
                size="small"
                placeholder="userId,逗号分隔"
                style="width: 160px; margin-right: 8px"
              />
              <el-button link type="primary" size="small" @click="onReplace(row)">保存成员</el-button>
            </template>
            <span v-else class="muted">不可编辑</span>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import {
  createGroup,
  isOidcGroupSource,
  listGroups,
  replaceGroupMembers,
  type GroupView
} from '@/admin/adminApi'

const groups = ref<GroupView[]>([])
const newName = ref('')
const memberDraft = reactive<Record<string, string>>({})
const busy = ref(false)
const msg = ref('')
const error = ref('')

async function reload() {
  busy.value = true
  error.value = ''
  try {
    groups.value = await listGroups()
    for (const g of groups.value) {
      memberDraft[g.id] = (g.memberIds ?? []).join(',')
    }
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    busy.value = false
  }
}

async function onCreate() {
  if (!newName.value.trim()) return
  await createGroup(newName.value.trim())
  newName.value = ''
  msg.value = '组已创建'
  await reload()
}

async function onReplace(row: GroupView) {
  const ids = (memberDraft[row.id] || '')
    .split(',')
    .map((s) => s.trim())
    .filter(Boolean)
  await replaceGroupMembers(row.id, ids)
  msg.value = '成员已更新'
  await reload()
}

onMounted(reload)
</script>

<style scoped>
.mb { margin-bottom: 12px; }
.ml { margin-left: 6px; }
.muted { color: var(--muted); font-size: 0.85rem; }
</style>
