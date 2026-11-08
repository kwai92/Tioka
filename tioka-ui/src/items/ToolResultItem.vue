<template>
  <div class="item tool-result" :class="{ fail: ok === false }">
    <div class="tag">tool_result · {{ name }}{{ ok === false ? ' · fail' : '' }}</div>
    <pre class="payload">{{ display }}</pre>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { RuntimeItemView } from './types'

const props = defineProps<{ item: RuntimeItemView }>()

const name = computed(() => String(props.item.content.name ?? ''))
const ok = computed(() =>
  typeof props.item.content.ok === 'boolean' ? props.item.content.ok : undefined
)
const display = computed(() => {
  const out = props.item.content.output
  if (typeof out === 'string') return out
  try {
    return JSON.stringify(props.item.content, null, 2)
  } catch {
    return String(out ?? '')
  }
})
</script>

<style scoped>
.tool-result { display: grid; gap: 4px; }
.tag { font-size: 11px; color: #7dd3fc; text-transform: uppercase; }
.fail .tag { color: #fca5a5; }
.payload {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
  font: inherit;
  font-size: 12px;
  max-height: 240px;
  overflow: auto;
}
</style>
