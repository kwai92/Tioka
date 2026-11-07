<template>
  <div class="item message" :class="{ pending: item.pending }">
    <pre class="text">{{ text }}</pre>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { RuntimeItemView } from './types'

const props = defineProps<{ item: RuntimeItemView }>()

const text = computed(() => {
  const t = props.item.content.text
  if (typeof t === 'string' && t) return t
  if (props.item.pending) return '发送中…'
  return ''
})
</script>

<style scoped>
.message { display: grid; gap: 4px; }
.text {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
  font: inherit;
}
.pending { opacity: 0.75; }
</style>
