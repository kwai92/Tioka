<template>
  <div class="item tool-call">
    <div class="tag">tool_call · {{ name }}</div>
    <pre class="payload">{{ body }}</pre>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { RuntimeItemView } from './types'

const props = defineProps<{ item: RuntimeItemView }>()

const name = computed(() => String(props.item.content.name ?? ''))
const body = computed(() => {
  try {
    return JSON.stringify(
      {
        callId: props.item.content.callId,
        name: props.item.content.name,
        arguments: props.item.content.arguments
      },
      null,
      2
    )
  } catch {
    return String(props.item.content.arguments ?? '')
  }
})
</script>

<style scoped>
.tool-call { display: grid; gap: 4px; }
.tag { font-size: 11px; color: #7dd3fc; text-transform: uppercase; }
.payload {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
  font: inherit;
  font-size: 12px;
}
</style>
