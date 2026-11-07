<template>
  <div class="page">
    <h1>Skills</h1>
    <p class="muted">导入 ZIP（需含 manifest.json + SKILL.md）并发布</p>

    <el-alert v-if="msg" :title="msg" type="success" show-icon class="mb" @close="msg = ''" />
    <el-alert v-if="error" :title="error" type="error" show-icon class="mb" @close="error = ''" />

    <el-card shadow="never" class="mb">
      <template #header>
        <div class="card-head">
          <span>导入 Skill ZIP</span>
          <el-button type="primary" size="small" :loading="uploading" :disabled="!file" @click="doImport">
            上传导入
          </el-button>
        </div>
      </template>
      <el-upload
        drag
        :auto-upload="false"
        :limit="1"
        accept=".zip,application/zip"
        :on-change="onFileChange"
        :on-remove="() => (file = null)"
      >
        <div class="el-upload__text">将 ZIP 拖到此处，或 <em>点击选择</em></div>
      </el-upload>
    </el-card>

    <el-card shadow="never">
      <template #header>
        <div class="card-head">
          <span>Skill 列表</span>
          <el-button size="small" @click="refresh" :loading="loading">刷新</el-button>
        </div>
      </template>
      <el-table :data="skills" size="small" stripe v-loading="loading">
        <el-table-column prop="id" label="ID" min-width="120" />
        <el-table-column prop="name" label="名称" width="160" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.published ? 'success' : 'info'" size="small">
              {{ row.published ? '已发布' : '草稿' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="SKILL.md 预览" min-width="220">
          <template #default="{ row }">
            <span class="preview">{{ (row.skillMd || '').slice(0, 80) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="160">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openShare(row.id)">分享</el-button>
            <el-button
              v-if="!row.published"
              link
              type="primary"
              size="small"
              @click="publish(row.id)"
            >
              发布
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <ResourceAclDialog v-model="shareOpen" resource-type="skill" :resource-id="shareId" />
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { api } from '../api'
import ResourceAclDialog from '@/components/ResourceAclDialog.vue'

const skills = ref([])
const file = ref(null)
const loading = ref(false)
const uploading = ref(false)
const msg = ref('')
const error = ref('')
const shareOpen = ref(false)
const shareId = ref('')

function openShare(id) {
  shareId.value = id
  shareOpen.value = true
}

async function refresh() {
  loading.value = true
  error.value = ''
  try {
    skills.value = await api('/api/skills')
  } catch (e) {
    error.value = e.message
    skills.value = []
  } finally {
    loading.value = false
  }
}

function onFileChange(uploadFile) {
  file.value = uploadFile?.raw || null
}

async function doImport() {
  if (!file.value) return
  uploading.value = true
  error.value = ''
  msg.value = ''
  try {
    const fd = new FormData()
    fd.append('file', file.value)
    const pkg = await api('/api/skills/import', { method: 'POST', body: fd })
    msg.value = `已导入 ${pkg.name} (${pkg.id})`
    file.value = null
    await refresh()
  } catch (e) {
    error.value = e.message
  } finally {
    uploading.value = false
  }
}

async function publish(id) {
  try {
    const pkg = await api(`/api/skills/${id}/publish`, { method: 'POST', body: '{}' })
    msg.value = `已发布 ${pkg.id}`
    await refresh()
  } catch (e) {
    error.value = e.message
  }
}

onMounted(refresh)
</script>

<style scoped>
.page { max-width: 960px; display: grid; gap: 8px; }
.muted { color: var(--muted); margin: 0 0 8px; }
.mb { margin-bottom: 12px; }
.card-head { display: flex; justify-content: space-between; align-items: center; }
.preview { color: var(--muted); font-size: 12px; }
</style>
