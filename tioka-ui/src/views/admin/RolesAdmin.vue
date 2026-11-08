<template>
  <div class="page">
    <h1>角色权限矩阵</h1>
    <el-alert v-if="msg" :title="msg" type="success" show-icon class="mb" @close="msg = ''" />
    <el-alert v-if="error" :title="error" type="error" show-icon class="mb" @close="error = ''" />

    <el-card shadow="never" class="mb">
      <el-select v-model="selectedCode" placeholder="选择角色" style="width: 280px" @change="onSelect">
        <el-option
          v-for="r in roles"
          :key="r.code"
          :label="`${r.name} (${r.code})${r.builtin ? ' · 内置' : ''}`"
          :value="r.code"
        />
      </el-select>
      <el-button class="ml" :disabled="!selected || selected.builtin" :loading="busy" type="primary" @click="save">
        保存权限
      </el-button>
      <p v-if="selected?.builtin" class="muted">内置角色只读，不可修改。</p>
    </el-card>

    <el-card v-if="selected" shadow="never">
      <div v-for="g in groups" :key="g.resource" class="group">
        <h3>{{ g.resource }}</h3>
        <el-checkbox-group v-model="checked" :disabled="selected.builtin">
          <el-checkbox v-for="p in g.permissions" :key="p" :label="p" :value="p">{{ p }}</el-checkbox>
        </el-checkbox-group>
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { listPermissionCatalog, listRoles, updateRole, type RoleView } from '@/admin/adminApi'
import { buildRolePermissionPayload, groupPermissionsByResource } from '@/admin/roleMatrix'

const roles = ref<RoleView[]>([])
const catalog = ref<string[]>([])
const selectedCode = ref('')
const selected = ref<RoleView | null>(null)
const checked = ref<string[]>([])
const busy = ref(false)
const msg = ref('')
const error = ref('')

const groups = computed(() => groupPermissionsByResource(catalog.value))

async function reload() {
  busy.value = true
  error.value = ''
  try {
    ;[roles.value, catalog.value] = await Promise.all([listRoles(), listPermissionCatalog()])
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    busy.value = false
  }
}

function onSelect(code: string) {
  selected.value = roles.value.find((r) => r.code === code) ?? null
  checked.value = [...(selected.value?.permissions ?? [])]
}

async function save() {
  if (!selected.value || selected.value.builtin) return
  busy.value = true
  try {
    const permissions = buildRolePermissionPayload(checked.value)
    const updated = await updateRole(selected.value.code, {
      name: selected.value.name,
      permissions
    })
    selected.value = updated
    checked.value = [...updated.permissions]
    msg.value = '权限已保存'
    await reload()
    selectedCode.value = updated.code
    onSelect(updated.code)
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    busy.value = false
  }
}

onMounted(reload)
</script>

<style scoped>
.mb { margin-bottom: 12px; }
.ml { margin-left: 12px; }
.muted { color: var(--muted); font-size: 0.85rem; }
.group { margin-bottom: 16px; }
.group h3 { margin: 0 0 8px; font-size: 0.95rem; text-transform: uppercase; color: var(--muted); }
</style>
