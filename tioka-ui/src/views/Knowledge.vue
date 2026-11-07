<template>
  <div class="page">
    <h1>知识台</h1>
    <p class="muted">上传 → 混合检索 → 点击引用定位原文</p>

    <el-alert v-if="msg" :title="msg" type="success" show-icon class="mb" @close="msg = ''" />
    <el-alert v-if="error" :title="error" type="error" show-icon class="mb" @close="error = ''" />

    <el-card shadow="never" class="mb">
      <template #header>
        <div class="card-head">
          <span>创建知识库</span>
        </div>
      </template>
      <el-form :inline="true" @submit.prevent>
        <el-form-item label="名称">
          <el-input v-model="name" placeholder="知识库名称" />
        </el-form-item>
        <el-form-item label="嵌入 binding">
          <el-select
            v-model="embeddingBindingId"
            clearable
            filterable
            placeholder="选择 kind=embedding"
            style="min-width: 280px"
            :disabled="!embeddingBindings.length"
          >
            <el-option
              v-for="b in embeddingBindings"
              :key="b.id"
              :label="b.label"
              :value="b.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button
            type="primary"
            :loading="busy"
            :disabled="!embeddingBindings.length"
            @click="createKb"
          >
            创建库
          </el-button>
        </el-form-item>
      </el-form>
      <p v-if="!embeddingBindings.length" class="hint">
        暂无可用嵌入 binding（需 kind=embedding、已启用且维度对齐配置）。请先在「模型」页配置。
      </p>
      <p v-if="kbId" class="kb-id">
        当前库：{{ kbId }}
        <template v-if="boundEmbeddingId"> · 嵌入：{{ boundEmbeddingId }}</template>
        <el-button link type="primary" size="small" class="share-btn" @click="openShare">分享</el-button>
      </p>
    </el-card>

    <ResourceAclDialog v-model="shareOpen" resource-type="kb" :resource-id="kbId" />

    <el-card v-if="kbId" shadow="never" class="mb">
      <template #header>
        <div class="card-head">
          <span>重嵌入进度</span>
          <el-button size="small" :loading="progressLoading" @click="refreshProgress">刷新</el-button>
        </div>
      </template>
      <template v-if="progress">
        <el-progress
          :percentage="progress.percent"
          :status="progress.pending > 0 ? undefined : 'success'"
        />
        <p class="progress-meta">
          REINDEX_PENDING {{ progress.pending }} / 共 {{ progress.total }}
          · READY {{ progress.ready }}
          · FAILED {{ progress.failed }}
          · EMBEDDING {{ progress.embedding }}
        </p>
        <el-alert
          v-if="progress.pending > 0"
          type="info"
          :closable="false"
          show-icon
          title="后台 ReindexJob 正在消化 REINDEX_PENDING（V119 / 维度统一后常见）"
        />
      </template>
      <p v-else class="muted">加载进度中…</p>
    </el-card>

    <el-card v-if="kbId" shadow="never" class="mb">
      <template #header>
        <div class="card-head">
          <span>文本入库</span>
          <el-button type="primary" size="small" :loading="busy" @click="ingest">入库</el-button>
        </div>
      </template>
      <el-form label-width="88px">
        <el-form-item label="标题">
          <el-input v-model="title" placeholder="文档标题" />
        </el-form-item>
        <el-form-item label="格式">
          <el-select v-model="format" style="width: 160px">
            <el-option label="Markdown" value="md" />
            <el-option label="HTML" value="html" />
            <el-option label="纯文本" value="txt" />
          </el-select>
        </el-form-item>
        <el-form-item label="正文">
          <el-input v-model="content" type="textarea" :rows="5" placeholder="文档正文" />
        </el-form-item>
      </el-form>
    </el-card>

    <el-card v-if="kbId" shadow="never" class="mb">
      <template #header>
        <div class="card-head">
          <span>文件上传</span>
          <el-button
            type="primary"
            size="small"
            :loading="uploading"
            :disabled="!uploadFile"
            @click="doUpload"
          >
            上传入库
          </el-button>
        </div>
      </template>
      <el-form label-width="88px" class="mb">
        <el-form-item label="可选标题">
          <el-input v-model="uploadTitle" placeholder="默认取文件名" clearable />
        </el-form-item>
      </el-form>
      <el-upload
        drag
        :auto-upload="false"
        :limit="1"
        accept=".pdf,.docx,.md,.txt,.html,.htm,application/pdf,text/*"
        :on-change="onFileChange"
        :on-remove="() => (uploadFile = null)"
      >
        <div class="el-upload__text">将文件拖到此处，或 <em>点击选择</em></div>
        <template #tip>
          <div class="el-upload__tip">调用 POST /bases/{id}/documents/upload；后端未就绪时会提示失败</div>
        </template>
      </el-upload>
    </el-card>

    <el-card v-if="kbId" shadow="never" class="mb">
      <template #header>
        <div class="card-head">
          <span>混合检索</span>
          <el-button type="primary" size="small" :loading="searching" @click="search">检索</el-button>
        </div>
      </template>
      <el-form :inline="true" @submit.prevent>
        <el-form-item label="检索词">
          <el-input v-model="query" placeholder="检索词" style="min-width: 260px" @keyup.enter="search" />
        </el-form-item>
      </el-form>

      <el-empty v-if="!hits.length && !searching" description="暂无命中" :image-size="64" />
      <ul class="hits">
        <li
          v-for="h in hits"
          :key="h.citationId"
          :class="{ active: h.citationId === activeCit }"
          @click="resolve(h.citationId)"
        >
          <div class="hit-top">
            <strong>{{ h.title || '未命名' }}</strong>
            <el-tag size="small" type="info">score={{ Number(h.score).toFixed(3) }}</el-tag>
          </div>
          <span class="hit-text">{{ h.text }}</span>
          <em class="hit-id">{{ h.citationId }}</em>
        </li>
      </ul>
    </el-card>

    <el-drawer
      v-model="drawerOpen"
      title="引用原文"
      direction="rtl"
      size="420px"
      :append-to-body="true"
    >
      <div v-loading="resolving" class="cite-panel">
        <p v-if="citeError" class="err">{{ citeError }}</p>
        <template v-else-if="citation">
          <div class="meta">
            <div><span class="k">标题</span>{{ citation.title || '—' }}</div>
            <div><span class="k">citationId</span>{{ citation.id }}</div>
            <div><span class="k">docId</span>{{ citation.docId || '—' }}</div>
            <div><span class="k">chunkId</span>{{ citation.chunkId || '—' }}</div>
            <div><span class="k">ordinal</span>{{ citation.ordinal ?? '—' }}</div>
          </div>
          <pre
            class="cite-body"
            v-html="highlightText(citation.text || '', query)"
          ></pre>
        </template>
      </div>
    </el-drawer>
  </div>
</template>

<script setup>
import { onMounted, onUnmounted, ref, watch } from 'vue'
import { api } from '../api'
import ResourceAclDialog from '@/components/ResourceAclDialog.vue'
import {
  createKnowledgeBase,
  fetchReindexProgress,
  listEmbeddingBindings
} from './knowledgeConsole'

const shareOpen = ref(false)
function openShare() {
  if (!kbId.value) return
  shareOpen.value = true
}

const name = ref('demo-kb')
const embeddingBindingId = ref('')
const embeddingBindings = ref([])
const boundEmbeddingId = ref('')
const kbId = ref('')
const title = ref('手册')
const content = ref('# Tioka\nretrieval citation vector hybrid search')
const format = ref('md')
const query = ref('retrieval')
const hits = ref([])
const citation = ref(null)
const activeCit = ref('')
const uploadFile = ref(null)
const uploadTitle = ref('')
const busy = ref(false)
const uploading = ref(false)
const searching = ref(false)
const resolving = ref(false)
const drawerOpen = ref(false)
const msg = ref('')
const error = ref('')
const citeError = ref('')
const progress = ref(null)
const progressLoading = ref(false)
let pollTimer = null

function escapeHtml(s) {
  return String(s)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

function highlightText(text, q) {
  const raw = escapeHtml(text || '')
  const tokens = String(q || '')
    .split(/\s+/)
    .map((t) => t.trim())
    .filter(Boolean)
  if (!tokens.length) return raw
  let out = raw
  for (const t of tokens) {
    const re = new RegExp(`(${t.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')})`, 'gi')
    out = out.replace(re, '<mark>$1</mark>')
  }
  return out
}

async function loadEmbeddingBindings() {
  try {
    embeddingBindings.value = await listEmbeddingBindings(api)
    if (!embeddingBindingId.value && embeddingBindings.value.length === 1) {
      embeddingBindingId.value = embeddingBindings.value[0].id
    }
  } catch (e) {
    error.value = e.message
    embeddingBindings.value = []
  }
}

async function refreshProgress() {
  if (!kbId.value) return
  progressLoading.value = true
  try {
    progress.value = await fetchReindexProgress(api, kbId.value)
  } catch (e) {
    error.value = e.message
  } finally {
    progressLoading.value = false
  }
}

function stopPoll() {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

function startPollIfNeeded() {
  stopPoll()
  if (!kbId.value) return
  pollTimer = setInterval(async () => {
    if (!kbId.value) return
    try {
      progress.value = await fetchReindexProgress(api, kbId.value)
      if (progress.value && progress.value.pending === 0) stopPoll()
    } catch {
      /* 轮询失败不打断主流程 */
    }
  }, 5000)
}

async function createKb() {
  if (!embeddingBindingId.value) {
    error.value = '请选择嵌入 binding'
    return
  }
  busy.value = true
  error.value = ''
  msg.value = ''
  try {
    const kb = await createKnowledgeBase(api, {
      name: name.value,
      embeddingBindingId: embeddingBindingId.value
    })
    kbId.value = kb.id
    boundEmbeddingId.value = kb.embeddingBindingId || embeddingBindingId.value
    msg.value = `已创建知识库 ${kb.id}`
    await refreshProgress()
    startPollIfNeeded()
  } catch (e) {
    error.value = e.message
  } finally {
    busy.value = false
  }
}

async function ingest() {
  if (!kbId.value) return
  busy.value = true
  error.value = ''
  msg.value = ''
  try {
    const doc = await api(`/api/knowledge/bases/${kbId.value}/documents`, {
      method: 'POST',
      body: JSON.stringify({
        title: title.value,
        content: content.value,
        sourceType: 'upload',
        format: format.value
      })
    })
    msg.value = `已入库 ${doc.id || doc.title || '文档'}`
    await refreshProgress()
  } catch (e) {
    error.value = e.message
  } finally {
    busy.value = false
  }
}

function onFileChange(file) {
  uploadFile.value = file?.raw || null
  if (!uploadTitle.value && file?.name) {
    uploadTitle.value = file.name.replace(/\.[^.]+$/, '')
  }
}

async function doUpload() {
  if (!kbId.value || !uploadFile.value) return
  uploading.value = true
  error.value = ''
  msg.value = ''
  try {
    const fd = new FormData()
    fd.append('file', uploadFile.value)
    if (uploadTitle.value.trim()) fd.append('title', uploadTitle.value.trim())
    const doc = await api(`/api/knowledge/bases/${kbId.value}/documents/upload`, {
      method: 'POST',
      body: fd
    })
    msg.value = `上传成功 ${doc.id || doc.title || ''}`
    uploadFile.value = null
    await refreshProgress()
  } catch (e) {
    error.value = `文件上传失败：${e.message || e}（请确认后端已提供 /documents/upload）`
  } finally {
    uploading.value = false
  }
}

async function search() {
  if (!kbId.value) return
  searching.value = true
  error.value = ''
  try {
    hits.value = await api(`/api/knowledge/bases/${kbId.value}/search`, {
      method: 'POST',
      body: JSON.stringify({ query: query.value, topK: 5 })
    })
  } catch (e) {
    error.value = e.message
    hits.value = []
  } finally {
    searching.value = false
  }
}

async function resolve(id) {
  activeCit.value = id
  drawerOpen.value = true
  resolving.value = true
  citeError.value = ''
  citation.value = null
  try {
    citation.value = await api(`/api/knowledge/citations/${id}`)
  } catch (e) {
    citeError.value = e.message
  } finally {
    resolving.value = false
  }
}

watch(kbId, (id) => {
  if (id) {
    refreshProgress()
    startPollIfNeeded()
  } else {
    stopPoll()
    progress.value = null
  }
})

onMounted(() => {
  loadEmbeddingBindings()
})

onUnmounted(() => {
  stopPoll()
})
</script>

<style scoped>
.page { max-width: 960px; display: grid; gap: 8px; }
.muted { color: var(--muted); margin: 0 0 8px; }
.mb { margin-bottom: 12px; }
.card-head { display: flex; justify-content: space-between; align-items: center; }
.kb-id { margin: 0; color: var(--muted); font-size: 12px; font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }
.hint { margin: 0; color: var(--muted); font-size: 12px; }
.progress-meta { margin: 8px 0 0; font-size: 12px; color: var(--muted); font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }
.hits { list-style: none; padding: 0; margin: 8px 0 0; }
.hits li {
  padding: 12px;
  border: 1px solid #334155;
  border-radius: 10px;
  margin: 8px 0;
  cursor: pointer;
  display: grid;
  gap: 6px;
  transition: border-color 0.15s, background 0.15s;
}
.hits li:hover { border-color: #22d3ee; background: rgba(34, 211, 238, 0.05); }
.hits li.active {
  border-color: var(--accent, #3d8bfd);
  background: rgba(61, 139, 253, 0.08);
}
.hit-top { display: flex; justify-content: space-between; align-items: center; gap: 8px; }
.hit-text { color: var(--text); font-size: 13px; line-height: 1.45; }
.hit-id { color: var(--muted); font-size: 11px; font-style: normal; font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }
.cite-panel { display: grid; gap: 12px; }
.cite-panel .meta { display: grid; gap: 6px; font-size: 13px; }
.cite-panel .k {
  display: inline-block;
  min-width: 88px;
  color: var(--muted);
  font-size: 12px;
}
.cite-body {
  margin: 0;
  padding: 12px;
  background: #0b1220;
  border-radius: 10px;
  white-space: pre-wrap;
  word-break: break-word;
  font-size: 13px;
  line-height: 1.5;
}
.cite-body :deep(mark) {
  background: rgba(250, 204, 21, 0.35);
  color: inherit;
  padding: 0 2px;
  border-radius: 2px;
}
.err { margin: 0; color: #ff8e8e; font-size: 12px; }
</style>
