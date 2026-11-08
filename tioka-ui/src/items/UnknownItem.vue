<template>
  <div class="item unknown">
    <div class="tag">{{ item.itemType || 'unknown' }}</div>
    <pre class="payload">{{ body }}</pre>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { RuntimeItemView } from './types'

const props = defineProps<{ item: RuntimeItemView }>()

const body = computed(() => {
  try {
    return JSON.stringify(props.item.content ?? {}, null, 2)
  } catch {
    return String(props.item.content ?? '')
  }
})
</script>

<style scoped>
.unknown { display: grid; gap: 4px; }
.tag { font-size: 11px; color: #c4b5fd; text-transform: uppercase; }
.payload {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
  font: inherit;
  font-size: 12px;
  color: var(--muted, #8aa0b5);
}
</style>
