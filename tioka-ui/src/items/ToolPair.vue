<template>
  <details class="item tool-pair" open>
    <summary>
      <span class="tag">tool</span>
      <strong>{{ name }}</strong>
      <span v-if="resultOk === false" class="fail">失败</span>
      <span v-else-if="result" class="ok">完成</span>
      <span v-else class="muted">调用中</span>
    </summary>
    <ToolCallItem :item="call" />
    <ToolResultItem v-if="result" :item="result" />
  </details>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { RuntimeItemView } from './types'
import ToolCallItem from './ToolCallItem.vue'
import ToolResultItem from './ToolResultItem.vue'

const props = defineProps<{
  call: RuntimeItemView
  result?: RuntimeItemView
}>()

const name = computed(() => String(props.call.content.name ?? ''))
const resultOk = computed(() =>
  props.result && typeof props.result.content.ok === 'boolean'
    ? props.result.content.ok
    : undefined
)
</script>

<style scoped>
.tool-pair {
  border: 1px solid #243041;
  border-radius: 10px;
  padding: 8px 10px;
  display: grid;
  gap: 8px;
}
summary {
  cursor: pointer;
  display: flex;
  gap: 8px;
  align-items: center;
  user-select: none;
}
.tag { font-size: 11px; color: #7dd3fc; text-transform: uppercase; }
.ok { font-size: 12px; color: #86efac; }
.fail { font-size: 12px; color: #fca5a5; }
.muted { font-size: 12px; color: var(--muted, #8aa0b5); }
</style>
