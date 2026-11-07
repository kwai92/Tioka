<template>
  <div class="item citation">
    <button type="button" class="cite-chip" :disabled="!citationId" @click="openCitation">
      <span class="tag">citation</span>
      <strong class="cite-title">{{ title }}</strong>
      <span v-if="snippet" class="cite-snippet">{{ snippet }}</span>
      <span v-if="citationId" class="cite-id">{{ citationId }}</span>
    </button>
    <el-drawer v-model="drawerOpen" title="引用原文" direction="rtl" size="420px" :append-to-body="true">
      <div class="cite-panel">
        <p v-if="citeLoading" class="muted">加载中…</p>
        <p v-else-if="citeError" class="err">{{ citeError }}</p>
        <template v-else-if="citeDetail">
          <div class="meta">
            <div><span class="k">标题</span>{{ citeDetail.title || '—' }}</div>
            <div><span class="k">citationId</span>{{ citeDetail.id }}</div>
            <div><span class="k">docId</span>{{ citeDetail.docId || '—' }}</div>
            <div><span class="k">chunkId</span>{{ citeDetail.chunkId || '—' }}</div>
          </div>
          <pre class="cite-body">{{ citeDetail.text || '' }}</pre>
        </template>
      </div>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { api } from '../api'
import type { RuntimeItemView } from './types'

type CitationDetail = {
  id: string
  docId?: string
  chunkId?: string
  text?: string
  title?: string
}

const props = defineProps<{ item: RuntimeItemView }>()

const drawerOpen = ref(false)
const citeLoading = ref(false)
const citeError = ref('')
const citeDetail = ref<CitationDetail | null>(null)

const citationId = computed(() => {
  const id = props.item.content.citationId ?? props.item.content.id
  return typeof id === 'string' ? id : ''
})
const title = computed(() => {
  const t = props.item.content.title
  if (typeof t === 'string' && t) return t
  return citationId.value ? `引用 ${citationId.value}` : '知识引用'
})
const snippet = computed(() => {
  const s = props.item.content.snippet ?? props.item.content.text
  return typeof s === 'string' ? s : ''
})

async function openCitation() {
  if (!citationId.value) return
  drawerOpen.value = true
  citeLoading.value = true
  citeError.value = ''
  citeDetail.value = null
  try {
    citeDetail.value = await api<CitationDetail>(`/api/knowledge/citations/${citationId.value}`)
  } catch (e: unknown) {
    citeError.value = e instanceof Error ? e.message : String(e)
  } finally {
    citeLoading.value = false
  }
}
</script>

<style scoped>
.citation { border-left: 3px solid #22d3ee; padding-left: 8px; }
.cite-chip {
  display: grid;
  gap: 4px;
  width: 100%;
  text-align: left;
  cursor: pointer;
  background: transparent;
  border: 1px solid #334155;
  border-radius: 10px;
  padding: 8px 10px;
  color: inherit;
  font: inherit;
}
.cite-chip:hover:not(:disabled) {
  border-color: #22d3ee;
  background: rgba(34, 211, 238, 0.08);
}
.cite-chip:disabled { cursor: not-allowed; opacity: 0.6; }
.tag { font-size: 11px; color: #67e8f9; text-transform: uppercase; }
.cite-title { font-size: 13px; }
.cite-snippet {
  font-size: 12px;
  color: var(--muted, #8aa0b5);
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.cite-id {
  font-size: 11px;
  color: #67e8f9;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}
.cite-panel { display: grid; gap: 12px; }
.meta { display: grid; gap: 6px; font-size: 13px; }
.k {
  display: inline-block;
  min-width: 88px;
  color: var(--muted, #8aa0b5);
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
}
.err { margin: 0; color: #ff8e8e; font-size: 12px; }
</style>
