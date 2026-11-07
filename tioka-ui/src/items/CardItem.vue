<template>
  <div class="item card" :class="{ tool: isToolApproval }">
    <template v-if="isToolApproval">
      <div class="card-head">
        <span class="tag">审批 · {{ toolName }}</span>
        <el-tag v-if="risk" size="small" :type="riskTagType" effect="dark">{{ risk }}</el-tag>
        <span v-if="reason" class="reason">{{ reason }}</span>
      </div>

      <template v-if="isPending">
        <label class="lbl">参数（可编辑）</label>
        <el-input
          v-model="argsText"
          type="textarea"
          :rows="6"
          class="args-editor"
          :disabled="submitting"
        />
        <el-input
          v-model="comment"
          type="textarea"
          :rows="2"
          placeholder="备注（可选）"
          :disabled="submitting"
        />
        <div class="actions">
          <el-button type="success" size="small" :loading="submitting" @click="submit(true)">
            批准
          </el-button>
          <el-button type="danger" size="small" plain :loading="submitting" @click="submit(false)">
            拒绝
          </el-button>
        </div>
        <p v-if="localError" class="err">{{ localError }}</p>
      </template>

      <template v-else>
        <div class="decided">
          <el-tag size="small" :type="decidedTagType">{{ decidedStatus }}</el-tag>
          <span v-if="decidedBy" class="by">审批人 {{ decidedBy }}</span>
        </div>
        <pre class="payload">{{ decidedArgsDisplay }}</pre>
        <p v-if="decidedComment" class="comment">备注：{{ decidedComment }}</p>
      </template>
    </template>

    <template v-else>
      <div class="tag">card · {{ kind || 'generic' }}</div>
      <pre class="payload">{{ genericBody }}</pre>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { api } from '../api'
import { decideApproval, getApproval } from './approvalApi'
import type { ApprovalDecisionView, RuntimeItemView } from './types'
import { parseArgsJson, validateToolArgs } from './validateArgs'

const props = defineProps<{
  item: RuntimeItemView
  approval?: ApprovalDecisionView | null
}>()

const emit = defineEmits<{
  submitted: []
}>()

const submitting = ref(false)
const localError = ref('')
const argsText = ref('{}')
const comment = ref('')
const remoteStatus = ref<string | null>(null)
const remoteDecidedBy = ref<string | null>(null)
const remoteComment = ref<string | null>(null)

const content = computed(() => props.item.content || {})
const kind = computed(() => String(content.value.kind ?? ''))
const isToolApproval = computed(() => kind.value === 'tool')
const approvalId = computed(() => String(content.value.approvalId ?? ''))
const toolName = computed(() => String(content.value.toolName ?? ''))
const risk = computed(() => {
  const r = content.value.risk
  return typeof r === 'string' ? r : ''
})
const reason = computed(() => {
  const r = content.value.reason
  return typeof r === 'string' ? r : ''
})
const inputSchema = computed(() => {
  const s = content.value.inputSchema
  return s && typeof s === 'object' && !Array.isArray(s)
    ? (s as Record<string, unknown>)
    : null
})

const decidedStatus = computed(() => {
  return (
    props.approval?.status ||
    remoteStatus.value ||
    (typeof content.value.status === 'string' ? content.value.status : '') ||
    'PENDING'
  )
})
const isPending = computed(() => decidedStatus.value === 'PENDING' || decidedStatus.value === '')
const decidedBy = computed(
  () => props.approval?.decidedBy || remoteDecidedBy.value || String(content.value.decidedBy ?? '')
)
const decidedComment = computed(() => remoteComment.value || '')

const riskTagType = computed(() => {
  switch (risk.value) {
    case 'READ':
      return 'info'
    case 'WRITE':
      return 'warning'
    case 'DESTRUCTIVE':
    case 'EXTERNAL_SEND':
      return 'danger'
    default:
      return 'info'
  }
})
const decidedTagType = computed(() => {
  switch (decidedStatus.value) {
    case 'APPROVED':
      return 'success'
    case 'REJECTED':
      return 'danger'
    case 'EXPIRED':
      return 'info'
    default:
      return 'warning'
  }
})

const decidedArgsDisplay = computed(() => {
  try {
    return JSON.stringify(content.value.args ?? {}, null, 2)
  } catch {
    return String(content.value.args ?? '')
  }
})
const genericBody = computed(() => {
  try {
    return JSON.stringify(content.value, null, 2)
  } catch {
    return ''
  }
})

watch(
  () => props.item.itemId,
  () => {
    try {
      argsText.value = JSON.stringify(content.value.args ?? {}, null, 2)
    } catch {
      argsText.value = '{}'
    }
    comment.value = ''
    localError.value = ''
    remoteStatus.value = null
    remoteDecidedBy.value = null
    remoteComment.value = null
  },
  { immediate: true }
)

watch(
  () => [approvalId.value, decidedStatus.value] as const,
  async ([id, status]) => {
    if (!id || !isToolApproval.value) return
    if (status && status !== 'PENDING') return
    if (props.approval?.status && props.approval.status !== 'PENDING') return
    try {
      const view = await getApproval(api, id)
      if (view?.status && view.status !== 'PENDING') {
        remoteStatus.value = view.status
        remoteDecidedBy.value = view.decidedBy || null
        remoteComment.value = view.comment || null
      }
    } catch {
      /* 列表渲染时 404/无权限不阻断 */
    }
  },
  { immediate: true }
)

async function submit(approved: boolean) {
  if (!approvalId.value) {
    localError.value = '缺少 approvalId'
    return
  }
  localError.value = ''
  let answer: { args?: Record<string, unknown> } | undefined
  if (approved) {
    const parsed = parseArgsJson(argsText.value)
    if (!parsed.ok) {
      localError.value = parsed.message
      return
    }
    const checked = validateToolArgs(parsed.args, inputSchema.value)
    if (!checked.ok) {
      localError.value = checked.message
      return
    }
    answer = { args: checked.args }
  }
  submitting.value = true
  try {
    await decideApproval(api, approvalId.value, {
      approved,
      answer,
      comment: comment.value.trim() || undefined
    })
    remoteStatus.value = approved ? 'APPROVED' : 'REJECTED'
    emit('submitted')
  } catch (e: unknown) {
    localError.value = e instanceof Error ? e.message : String(e)
  } finally {
    submitting.value = false
  }
}
</script>

<style scoped>
.card {
  border-left: 3px solid var(--card-suspend-accent, #fbbf24);
  padding-left: 8px;
  background: rgba(251, 191, 36, 0.08);
  border-radius: 0 8px 8px 0;
  display: grid;
  gap: 8px;
}
.card-head {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}
.tag {
  font-size: 11px;
  color: var(--card-suspend-accent, #fbbf24);
  text-transform: uppercase;
}
.reason { font-size: 13px; font-weight: 600; }
.lbl { font-size: 12px; color: var(--muted, #8aa0b5); }
.actions { display: flex; gap: 8px; }
.err { margin: 0; color: #ff8e8e; font-size: 12px; }
.decided { display: flex; gap: 8px; align-items: center; }
.by { font-size: 12px; color: var(--muted, #8aa0b5); }
.comment { margin: 0; font-size: 12px; color: var(--muted, #8aa0b5); }
.payload {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
  font: inherit;
  font-size: 12px;
  background: #0b1220;
  padding: 8px;
  border-radius: 8px;
}
</style>
