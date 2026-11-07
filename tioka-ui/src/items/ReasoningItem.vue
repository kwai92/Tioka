<template>
  <details class="item reasoning">
    <summary>思考过程</summary>
    <pre class="text">{{ text }}</pre>
  </details>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { RuntimeItemView } from './types'

const props = defineProps<{ item: RuntimeItemView }>()

const text = computed(() => {
  const r = props.item.content.reasoning
  if (typeof r === 'string') return r
  const t = props.item.content.text
  return typeof t === 'string' ? t : ''
})
</script>

<style scoped>
.reasoning {
  border-left: 3px solid #64748b;
  padding-left: 8px;
  color: var(--muted, #8aa0b5);
  font-size: 13px;
}
summary { cursor: pointer; user-select: none; }
.text {
  margin: 6px 0 0;
  white-space: pre-wrap;
  word-break: break-word;
  font: inherit;
}
</style>
