<template>
  <div class="item-list">
    <div
      v-for="(unit, idx) in units"
      :key="unitKey(unit, idx)"
      class="bubble"
      :class="[roleOf(unit), { pending: isPending(unit) }]"
    >
      <ItemRenderer
        v-if="unit.kind === 'tool_pair'"
        :item="unit.call"
        :pair-result="unit.result"
        @submitted="emit('submitted')"
      />
      <ItemRenderer
        v-else
        :item="unit.item"
        :approval="approvalFor(unit.item)"
        @submitted="emit('submitted')"
      />
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { groupItemsForRender, type RenderUnit } from './groupItems'
import ItemRenderer from './ItemRenderer.vue'
import type { ApprovalDecisionView, RuntimeItemView } from './types'

const props = defineProps<{
  items: RuntimeItemView[]
  approvals?: Record<string, ApprovalDecisionView>
}>()

const emit = defineEmits<{
  submitted: []
}>()

const units = computed(() => groupItemsForRender(props.items))

function unitKey(unit: RenderUnit, idx: number): string {
  if (unit.kind === 'tool_pair') {
    return `pair:${unit.call.itemId}:${unit.result?.itemId || ''}`
  }
  return unit.item.itemId || `i-${idx}`
}

function roleOf(unit: RenderUnit): string {
  const item = unit.kind === 'tool_pair' ? unit.call : unit.item
  return item.role || 'assistant'
}

function isPending(unit: RenderUnit): boolean {
  if (unit.kind === 'tool_pair') return !!unit.call.pending
  return !!unit.item.pending
}

function approvalFor(item: RuntimeItemView): ApprovalDecisionView | null {
  if (item.itemType !== 'card') return null
  const id = typeof item.content.approvalId === 'string' ? item.content.approvalId : ''
  if (!id || !props.approvals) return null
  return props.approvals[id] || null
}
</script>

<style scoped>
.item-list { display: grid; gap: 10px; align-content: start; }
.bubble { max-width: 85%; padding: 10px 12px; border-radius: 12px; display: grid; gap: 6px; }
.bubble.user { justify-self: end; background: #2a4a74; }
.bubble.assistant,
.bubble.tool,
.bubble.system { justify-self: start; background: #1b2735; }
.bubble.pending { opacity: 0.75; }
</style>
