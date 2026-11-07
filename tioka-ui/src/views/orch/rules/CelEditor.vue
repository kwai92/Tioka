<template>
  <div class="cel-editor" data-testid="cel-editor">
    <div class="gutter" aria-hidden="true">
      <div
        v-for="n in lineCount"
        :key="n"
        class="ln"
        :class="{ err: celError && celError.line === n }"
      >
        {{ n }}
      </div>
    </div>
    <textarea
      class="cel-input"
      :value="modelValue"
      :rows="Math.max(6, lineCount)"
      spellcheck="false"
      @input="onInput"
    />
  </div>
  <p v-if="celError" class="cel-err" data-testid="cel-error-loc">
    行列 {{ celError.line }}:{{ celError.column }} — {{ celError.message }}
  </p>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { CelErrorLocation } from './parseCelError'

const props = defineProps<{
  modelValue: string
  celError?: CelErrorLocation | null
}>()

const emit = defineEmits<{ 'update:modelValue': [string] }>()

const lineCount = computed(() => Math.max(1, (props.modelValue || '').split('\n').length))

function onInput(e: Event) {
  emit('update:modelValue', (e.target as HTMLTextAreaElement).value)
}
</script>

<style scoped>
.cel-editor {
  display: grid;
  grid-template-columns: 36px 1fr;
  border: 1px solid var(--border, #2a3a4d);
  border-radius: 6px;
  overflow: hidden;
  background: var(--panel, #0f1720);
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 13px;
}
.gutter {
  background: #0a1118;
  color: #6b7c90;
  padding: 8px 0;
  text-align: right;
  user-select: none;
  line-height: 1.5;
}
.ln {
  padding: 0 8px;
}
.ln.err {
  color: #f87171;
  font-weight: 600;
  background: rgba(248, 113, 113, 0.12);
}
.cel-input {
  width: 100%;
  border: 0;
  resize: vertical;
  padding: 8px 10px;
  background: transparent;
  color: inherit;
  line-height: 1.5;
  outline: none;
  font: inherit;
}
.cel-err {
  margin: 6px 0 0;
  color: #f87171;
  font-size: 13px;
}
</style>
