<template>
  <div class="data-item">
    <div class="meta">
      <span class="badge">{{ outcome || 'data' }}</span>
      <span v-if="schemaId" class="schema">{{ schemaId }}</span>
    </div>
    <component :is="schemaComp" v-if="schemaComp" :item="item" :payload="payload" />
    <pre v-else class="json">{{ pretty }}</pre>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { RuntimeItemView } from './types'
import { resolveDataItemRenderer } from './dataItemRegistry'

const props = defineProps<{
  item: RuntimeItemView
}>()

const payload = computed(() => {
  const raw = props.item.content
  if (raw && typeof raw === 'object') return raw as Record<string, unknown>
  return {}
})

const schemaId = computed(() => {
  const p = payload.value
  const id = p.schemaId ?? p.schema_id
  return typeof id === 'string' ? id : undefined
})

const outcome = computed(() => {
  const o = payload.value.outcome
  return typeof o === 'string' ? o : undefined
})

const schemaComp = computed(() => resolveDataItemRenderer(schemaId.value))

const pretty = computed(() => {
  try {
    return JSON.stringify(payload.value, null, 2)
  } catch {
    return String(props.item.content)
  }
})
</script>

<style scoped>
.data-item {
  display: grid;
  gap: 8px;
  font-size: 0.9rem;
}
.meta {
  display: flex;
  gap: 8px;
  align-items: center;
}
.badge {
  border-radius: 4px;
  padding: 2px 8px;
  background: #243447;
  font-size: 0.75rem;
}
.schema {
  color: var(--muted);
  font-size: 0.8rem;
}
.json {
  margin: 0;
  overflow: auto;
  padding: 8px;
  border-radius: 8px;
  background: #0f172a;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 0.8rem;
}
</style>
