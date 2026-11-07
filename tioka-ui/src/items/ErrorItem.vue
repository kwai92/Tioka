<template>
  <div class="item error">
    <span class="tag">error</span>
    <pre class="text">{{ text }}</pre>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { RuntimeItemView } from './types'

const props = defineProps<{ item: RuntimeItemView }>()

const text = computed(() => {
  const m = props.item.content.message ?? props.item.content.text ?? props.item.content.error
  if (typeof m === 'string') return m
  try {
    return JSON.stringify(props.item.content, null, 2)
  } catch {
    return 'error'
  }
})
</script>

<style scoped>
.error {
  border-left: 3px solid #f87171;
  padding-left: 8px;
  background: rgba(248, 113, 113, 0.08);
  border-radius: 0 8px 8px 0;
}
.tag { font-size: 11px; color: #fca5a5; text-transform: uppercase; }
.text {
  margin: 4px 0 0;
  white-space: pre-wrap;
  word-break: break-word;
  font: inherit;
  color: #fecaca;
}
</style>
