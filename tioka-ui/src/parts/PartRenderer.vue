<template>
  <div class="part" :class="[`type-${norm.type}`, modeClass]">
    <template v-if="norm.type === 'text'">
      <pre class="text">{{ norm.text }}</pre>
    </template>

    <template v-else-if="norm.type === 'tool_call'">
      <div class="tag">tool_call</div>
      <pre class="payload">{{ displayBody }}</pre>
    </template>

    <template v-else-if="norm.type === 'tool_result'">
      <div class="tag tool">tool_result</div>
      <pre class="payload">{{ displayBody }}</pre>
    </template>

    <template v-else-if="norm.type === 'citation'">
      <button type="button" class="cite-chip" @click="openCitation" :disabled="!citationId">
        <span class="tag cite">citation</span>
        <strong class="cite-title">{{ citationTitle }}</strong>
        <span v-if="citationSnippet" class="cite-snippet">{{ citationSnippet }}</span>
        <span v-if="citationId" class="cite-id">{{ citationId }}</span>
      </button>
      <el-drawer
        v-model="drawerOpen"
        title="引用原文"
        direction="rtl"
        size="420px"
        :append-to-body="true"
      >
        <div v-loading="citeLoading" class="cite-panel">
          <p v-if="citeError" class="err">{{ citeError }}</p>
          <template v-else-if="citeDetail">
            <div class="meta">
              <div><span class="k">标题</span>{{ citeDetail.title || '—' }}</div>
              <div><span class="k">citationId</span>{{ citeDetail.id }}</div>
              <div><span class="k">docId</span>{{ citeDetail.docId || '—' }}</div>
              <div><span class="k">chunkId</span>{{ citeDetail.chunkId || '—' }}</div>
              <div><span class="k">ordinal</span>{{ citeDetail.ordinal ?? '—' }}</div>
            </div>
            <pre class="cite-body">{{ citeDetail.text || '' }}</pre>
          </template>
          <p v-else class="muted">点击引用加载原文定位信息</p>
        </div>
      </el-drawer>
    </template>

    <template v-else-if="norm.type === 'interactive_card'">
      <div class="card-head">
        <span class="tag continue">continue · 续答</span>
        <span class="copy">{{ continueCopy }}</span>
      </div>
      <pre class="payload">{{ displayBody }}</pre>
      <div class="actions continue-actions">
        <el-input
          v-model="continueAnswer"
          type="textarea"
          :rows="2"
          placeholder="输入后续回复…"
          :disabled="!conversationId || submitting"
        />
        <el-button
          type="success"
          size="small"
          :loading="submitting"
          :disabled="!conversationId || !continueAnswer.trim()"
          @click="submitContinue"
        >
          {{ continueCta }}
        </el-button>
      </div>
      <p v-if="localError" class="err">{{ localError }}</p>
    </template>

    <template v-else-if="norm.type === 'approval_card'">
      <div class="card-head">
        <span class="tag suspend">suspend · 审批挂起</span>
        <span class="copy warn">{{ approvalCopy }}</span>
      </div>
      <pre class="payload">{{ displayBody }}</pre>
      <div class="actions suspend-actions">
        <el-button
          type="success"
          size="small"
          :loading="submitting"
          :disabled="!conversationId || !attentionId || submitting"
          @click="submitApproval(true)"
        >
          通过
        </el-button>
        <el-button
          type="danger"
          size="small"
          plain
          :loading="submitting"
          :disabled="!conversationId || !attentionId || submitting"
          @click="submitApproval(false)"
        >
          拒绝
        </el-button>
      </div>
      <p v-if="localError" class="err">{{ localError }}</p>
    </template>

    <template v-else>
      <div class="tag fallback">{{ norm.type || 'unknown' }}</div>
      <pre class="payload">{{ displayBody }}</pre>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { api } from '../api'
import { normalizePart, type PartInput } from './normalizePart'

type CitationDetail = {
  id: string
  docId?: string
  chunkId?: string
  text?: string
  title?: string
  ordinal?: number
}

const props = defineProps<{
  part: PartInput
  conversationId?: string
  messageId?: string
}>()

const emit = defineEmits<{
  submitted: []
}>()

const norm = computed(() => normalizePart(props.part))
const continueAnswer = ref('')
const submitting = ref(false)
const localError = ref('')

const drawerOpen = ref(false)
const citeLoading = ref(false)
const citeError = ref('')
const citeDetail = ref<CitationDetail | null>(null)

const displayBody = computed(() => {
  if (norm.value.text) return norm.value.text
  const p = norm.value.payload
  if (p && Object.keys(p).length) {
    try {
      return JSON.stringify(p, null, 2)
    } catch {
      return String(p)
    }
  }
  return ''
})

const modeClass = computed(() => {
  if (norm.value.type === 'interactive_card') return 'mode-continue'
  if (norm.value.type === 'approval_card') return 'mode-suspend'
  if (norm.value.type === 'citation') return 'mode-cite'
  return ''
})

const citationId = computed(() => {
  const id = norm.value.payload?.citationId ?? norm.value.payload?.id
  return typeof id === 'string' ? id : ''
})

const citationTitle = computed(() => {
  const t = norm.value.payload?.title
  if (typeof t === 'string' && t) return t
  return citationId.value ? `引用 ${citationId.value}` : '知识引用'
})

const citationSnippet = computed(() => {
  const s = norm.value.payload?.snippet ?? norm.value.payload?.text
  if (typeof s === 'string' && s) return s
  return norm.value.text || ''
})

const continueCopy = computed(() => {
  const c = norm.value.payload?.copy
  return typeof c === 'string' && c ? c : '请选择后续'
})

const continueCta = computed(() => {
  const c = norm.value.payload?.cta
  return typeof c === 'string' && c ? c : '提交续答'
})

const approvalCopy = computed(() => {
  const c = norm.value.payload?.copy
  return typeof c === 'string' && c ? c : '需审批后继续'
})

const attentionId = computed(() => {
  const id = norm.value.payload?.attentionId
  return typeof id === 'string' ? id : ''
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

async function submitContinue() {
  if (!props.conversationId || !continueAnswer.value.trim()) return
  submitting.value = true
  localError.value = ''
  try {
    await api('/api/chat/cards/continue/submit', {
      method: 'POST',
      body: JSON.stringify({
        conversationId: props.conversationId,
        messageIdOrPartHint: props.messageId || '',
        answerText: continueAnswer.value.trim()
      })
    })
    continueAnswer.value = ''
    emit('submitted')
  } catch (e: unknown) {
    localError.value = e instanceof Error ? e.message : String(e)
  } finally {
    submitting.value = false
  }
}

async function submitApproval(approved: boolean) {
  if (!props.conversationId || !attentionId.value) return
  submitting.value = true
  localError.value = ''
  try {
    await api('/api/chat/cards/approval/submit', {
      method: 'POST',
      body: JSON.stringify({
        conversationId: props.conversationId,
        attentionId: attentionId.value,
        approved
      })
    })
    emit('submitted')
  } catch (e: unknown) {
    localError.value = e instanceof Error ? e.message : String(e)
  } finally {
    submitting.value = false
  }
}
</script>

<style scoped>
.part { display: grid; gap: 6px; }
.text, .payload {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
  font: inherit;
}
.card-head {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 8px;
}
.copy {
  font-size: 13px;
  font-weight: 600;
  color: var(--card-continue-accent, #86efac);
}
.copy.warn {
  color: var(--card-suspend-accent, #fbbf24);
}
.tag {
  font-size: 11px;
  color: var(--muted, #8aa0b5);
  text-transform: uppercase;
}
.tag.tool { color: #7dd3fc; }
.tag.continue { color: var(--card-continue-accent, #86efac); }
.tag.suspend { color: var(--card-suspend-accent, #fbbf24); }
.tag.cite { color: #67e8f9; }
.tag.fallback { color: #c4b5fd; }
.mode-continue {
  border-left: 3px solid var(--card-continue-accent, #86efac);
  padding-left: 8px;
  background: rgba(134, 239, 172, 0.06);
  border-radius: 0 8px 8px 0;
}
.mode-suspend {
  border-left: 3px solid var(--card-suspend-accent, #fbbf24);
  padding-left: 8px;
  background: rgba(251, 191, 36, 0.08);
  border-radius: 0 8px 8px 0;
}
.mode-cite {
  border-left: 3px solid #22d3ee;
  padding-left: 8px;
  background: rgba(34, 211, 238, 0.06);
  border-radius: 0 8px 8px 0;
}
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
.cite-chip:disabled {
  cursor: not-allowed;
  opacity: 0.6;
}
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
.cite-panel .meta { display: grid; gap: 6px; font-size: 13px; }
.cite-panel .k {
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
  line-height: 1.5;
}
.muted { color: var(--muted, #8aa0b5); margin: 0; }
.actions {
  display: grid;
  gap: 8px;
  margin-top: 4px;
}
.continue-actions { grid-template-columns: 1fr auto; align-items: end; }
.suspend-actions { grid-template-columns: auto auto; justify-content: start; }
.err { margin: 0; color: #ff8e8e; font-size: 12px; }
</style>
