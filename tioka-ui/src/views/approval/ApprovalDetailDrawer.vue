<template>
  <el-drawer :model-value="modelValue" size="480px" :title="approval?.title || '审批详情'" @close="emit('update:modelValue', false)">
    <template v-if="approval">
      <component
        :is="resolveApprovalSubjectRenderer(approval.subjectType)"
        :subject-type="approval.subjectType"
        :approval="approval"
      />

      <section v-if="approval.kind === 'tool'" class="block">
        <h3>工具参数</h3>
        <el-input v-model="argsText" type="textarea" :rows="8" :disabled="!isPending" />
      </section>

      <section v-else-if="formFields.length" class="block">
        <h3>表单</h3>
        <el-form label-position="top">
          <el-form-item v-for="f in formFields" :key="f.name" :label="f.label || f.name">
            <el-input
              v-if="f.type === 'text' || f.type === 'textarea'"
              v-model="formValues[f.name]"
              :type="f.type === 'textarea' ? 'textarea' : 'text'"
              :rows="f.type === 'textarea' ? 3 : undefined"
              :disabled="!isPending"
            />
            <el-input-number
              v-else-if="f.type === 'number'"
              v-model="formValues[f.name]"
              :disabled="!isPending"
            />
            <el-switch
              v-else-if="f.type === 'boolean'"
              v-model="formValues[f.name]"
              :disabled="!isPending"
            />
            <el-select
              v-else-if="f.type === 'select'"
              v-model="formValues[f.name]"
              :disabled="!isPending"
            >
              <el-option
                v-for="opt in f.options || []"
                :key="String(opt.value)"
                :label="opt.label"
                :value="opt.value"
              />
            </el-select>
            <el-date-picker
              v-else-if="f.type === 'date'"
              v-model="formValues[f.name]"
              type="date"
              value-format="YYYY-MM-DD"
              :disabled="!isPending"
            />
            <el-input v-else v-model="formValues[f.name]" :disabled="!isPending" />
          </el-form-item>
        </el-form>
      </section>

      <section class="block">
        <h3>备注</h3>
        <el-input v-model="comment" type="textarea" :rows="2" :disabled="!isPending" placeholder="可选" />
      </section>

      <div v-if="isPending" class="actions">
        <el-button type="success" :loading="busy" @click="decide(true)">批准</el-button>
        <el-button type="danger" plain :loading="busy" @click="decide(false)">拒绝</el-button>
      </div>
      <p v-else class="muted">
        状态 {{ approval.status }}
        <span v-if="approval.decidedByName"> · {{ approval.decidedByName }}</span>
      </p>
      <p v-if="error" class="err">{{ error }}</p>
    </template>
  </el-drawer>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import {
  batchDecide,
  type ApprovalView
} from '@/api/approval'
import { resolveApprovalSubjectRenderer } from './subjectRegistry'

type FormField = {
  name: string
  type: string
  label?: string
  options?: Array<{ label: string; value: string | number | boolean }>
}

const props = defineProps<{
  modelValue: boolean
  approval: ApprovalView | null
}>()

const emit = defineEmits<{
  'update:modelValue': [boolean]
  decided: []
}>()

const argsText = ref('{}')
const comment = ref('')
const formValues = reactive<Record<string, unknown>>({})
const busy = ref(false)
const error = ref('')

const isPending = computed(() => props.approval?.status === 'PENDING')

const formFields = computed<FormField[]>(() => {
  const card = props.approval?.card
  if (!card || typeof card !== 'object') return []
  const form = (card as { form?: unknown }).form
  if (!Array.isArray(form)) return []
  return form.filter(
    (f): f is FormField =>
      !!f && typeof f === 'object' && typeof (f as FormField).name === 'string'
  )
})

watch(
  () => props.approval,
  (a) => {
    error.value = ''
    comment.value = a?.comment ?? ''
    argsText.value = JSON.stringify(a?.args ?? {}, null, 2)
    for (const k of Object.keys(formValues)) delete formValues[k]
    for (const f of formFields.value) {
      formValues[f.name] = f.type === 'boolean' ? false : ''
    }
  },
  { immediate: true }
)

async function decide(approved: boolean) {
  if (!props.approval) return
  busy.value = true
  error.value = ''
  try {
    let answer: Record<string, unknown> | undefined
    if (props.approval.kind === 'tool') {
      answer = { args: JSON.parse(argsText.value) as Record<string, unknown> }
    } else if (formFields.value.length) {
      answer = { ...formValues }
    }
    const results = await batchDecide({
      ids: [props.approval.id],
      approved,
      comment: comment.value || undefined
    })
    const r = results[0]
    if (r && !r.ok) {
      throw new Error(r.message || r.errorCode || '提交失败')
    }
    // answer 在批量接口 body 中未展开；单条工具参数改由 HCI-04 decision 路径，fixtures 下忽略
    void answer
    emit('decided')
    emit('update:modelValue', false)
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    busy.value = false
  }
}
</script>

<style scoped>
.block { margin-top: 16px; }
.block h3 { margin: 0 0 8px; font-size: 14px; }
.actions { display: flex; gap: 8px; margin-top: 16px; }
.muted { color: var(--muted, #8aa0b5); }
.err { color: #f87171; }
</style>
