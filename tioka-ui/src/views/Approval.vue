<template>
  <div class="page">
    <h1>审批台</h1>
    <p class="muted">处理 Orch Attention（suspend · 需审批后继续）</p>

    <el-alert v-if="error" :title="error" type="error" show-icon class="mb" @close="error = ''" />

    <div class="row mb">
      <el-button type="primary" :loading="loading" @click="refresh">刷新</el-button>
      <el-button :loading="demoBusy" @click="demo">演示：开一条挂起</el-button>
    </div>

    <el-empty v-if="!loading && !items.length" description="暂无待审批" />

    <el-card v-for="a in items" :key="a.id" shadow="never" class="att-card">
      <template #header>
        <div class="card-head">
          <div>
            <el-tag type="warning" size="small" effect="dark">suspend</el-tag>
            <strong class="title">{{ a.title }}</strong>
          </div>
          <span class="muted meta">{{ a.id }} · run {{ a.runId }}</span>
        </div>
        <p class="copy">需审批后继续</p>
      </template>
      <pre class="payload">{{ JSON.stringify(a.card, null, 2) }}</pre>
      <div class="row">
        <el-button type="success" size="small" :loading="answeringId === a.id" @click="answer(a.id, true)">
          通过 · 已批准续跑
        </el-button>
        <el-button type="danger" size="small" plain :loading="answeringId === a.id" @click="answer(a.id, false)">
          拒绝
        </el-button>
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { api } from '../api'

const items = ref([])
const error = ref('')
const loading = ref(false)
const demoBusy = ref(false)
const answeringId = ref('')

async function refresh() {
  error.value = ''
  loading.value = true
  try {
    items.value = await api('/api/orch/attentions')
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

async function answer(id, approved) {
  answeringId.value = id
  error.value = ''
  try {
    await api(`/api/orch/attentions/${id}/answer`, {
      method: 'POST',
      body: JSON.stringify({ approved })
    })
    await refresh()
  } catch (e) {
    error.value = e.message
  } finally {
    answeringId.value = ''
  }
}

async function demo() {
  demoBusy.value = true
  error.value = ''
  try {
    const runId = await api('/api/orch/events', {
      method: 'POST',
      body: JSON.stringify({ eventType: 'ui-demo', idempotencyKey: 'ui-' + Date.now() })
    })
    await api('/api/orch/runs/claim', { method: 'POST', body: JSON.stringify({ workerId: 'ui' }) })
    await api('/api/orch/attentions', {
      method: 'POST',
      body: JSON.stringify({
        runId,
        title: 'UI 演示审批',
        card: { mode: 'suspend', visual: 'approval-suspend', copy: '需审批后继续', cta: '去审批' }
      })
    })
    await refresh()
  } catch (e) {
    error.value = e.message
  } finally {
    demoBusy.value = false
  }
}

onMounted(refresh)
</script>

<style scoped>
.page { max-width: 880px; display: grid; gap: 12px; }
.muted { color: var(--muted); }
.mb { margin-bottom: 4px; }
.row { display: flex; gap: 8px; flex-wrap: wrap; }
.att-card { border: 1px solid #243041; background: rgba(251, 191, 36, 0.04); }
.card-head { display: flex; justify-content: space-between; gap: 12px; flex-wrap: wrap; align-items: baseline; }
.title { margin-left: 8px; }
.meta { font-size: 12px; }
.copy {
  margin: 8px 0 0;
  font-size: 13px;
  font-weight: 600;
  color: var(--card-suspend-accent, #fbbf24);
}
.payload {
  background: #0b1220;
  padding: 8px;
  border-radius: 8px;
  overflow: auto;
  margin: 0 0 12px;
  font-size: 12px;
}
</style>
