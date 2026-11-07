<template>
  <div class="item status">
    <span class="tag">status</span>
    <span>{{ label }}</span>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { RuntimeItemView } from './types'

const props = defineProps<{ item: RuntimeItemView }>()

const label = computed(() => {
  const kind = props.item.content.kind
  if (typeof kind === 'string' && kind) return kind
  try {
    return JSON.stringify(props.item.content)
  } catch {
    return 'status'
  }
})
</script>

<style scoped>
.status {
  display: flex;
  gap: 8px;
  align-items: center;
  font-size: 12px;
  color: var(--muted, #8aa0b5);
}
.tag { text-transform: uppercase; font-size: 11px; color: #94a3b8; }
</style>
