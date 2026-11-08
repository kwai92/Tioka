<template>
  <el-dialog
    :model-value="modelValue"
    :title="`分享 · ${resourceType}/${resourceId}`"
    width="560px"
    @update:model-value="emit('update:modelValue', $event)"
    @open="load"
  >
    <el-alert v-if="error" :title="error" type="error" show-icon class="mb" @close="error = ''" />
    <el-table :data="entries" size="small" stripe v-loading="busy">
      <el-table-column label="主体类型" width="110">
        <template #default="{ row }">
          <el-select v-model="row.subjectType" size="small" style="width: 100%">
            <el-option label="user" value="user" />
            <el-option label="group" value="group" />
            <el-option label="role" value="role" />
          </el-select>
        </template>
      </el-table-column>
      <el-table-column label="主体 ID" min-width="120">
        <template #default="{ row }">
          <el-input v-model="row.subjectId" size="small" />
        </template>
      </el-table-column>
      <el-table-column label="级别" width="120">
        <template #default="{ row }">
          <el-select v-model="row.level" size="small" style="width: 100%">
            <el-option label="VIEW" value="VIEW" />
            <el-option label="USE" value="USE" />
            <el-option label="EDIT" value="EDIT" />
            <el-option label="OWNER" value="OWNER" />
          </el-select>
        </template>
      </el-table-column>
      <el-table-column label="" width="60">
        <template #default="{ $index }">
          <el-button link type="danger" size="small" @click="entries.splice($index, 1)">删</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-button class="mt" size="small" @click="addRow">添加条目</el-button>
    <template #footer>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="busy" @click="save">保存</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import { listAcl, replaceAcl, type AclEntry } from '@/admin/adminApi'

const props = defineProps<{
  modelValue: boolean
  resourceType: string
  resourceId: string
}>()
const emit = defineEmits<{ 'update:modelValue': [boolean]; saved: [] }>()

const entries = ref<AclEntry[]>([])
const busy = ref(false)
const error = ref('')

watch(
  () => [props.modelValue, props.resourceType, props.resourceId] as const,
  ([open]) => {
    if (open) void load()
  }
)

async function load() {
  if (!props.resourceType || !props.resourceId) return
  busy.value = true
  error.value = ''
  try {
    entries.value = (await listAcl(props.resourceType, props.resourceId)).map((e) => ({ ...e }))
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : String(e)
    entries.value = []
  } finally {
    busy.value = false
  }
}

function addRow() {
  entries.value.push({ subjectType: 'user', subjectId: '', level: 'VIEW' })
}

async function save() {
  busy.value = true
  error.value = ''
  try {
    const body = entries.value.filter((e) => e.subjectId?.trim())
    await replaceAcl(props.resourceType, props.resourceId, body)
    emit('saved')
    emit('update:modelValue', false)
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    busy.value = false
  }
}
</script>

<style scoped>
.mb { margin-bottom: 12px; }
.mt { margin-top: 12px; }
</style>
