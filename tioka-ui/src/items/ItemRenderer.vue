<template>
  <component
    :is="comp"
    v-bind="compProps"
    @submitted="emit('submitted')"
  />
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { ApprovalDecisionView, RuntimeItemView } from './types'
import { resolveItemRenderer } from './itemRegistry'

const props = defineProps<{
  item: RuntimeItemView
  /** tool_call 成对折叠时传入对应 result */
  pairResult?: RuntimeItemView
  approval?: ApprovalDecisionView | null
}>()

const emit = defineEmits<{
  submitted: []
}>()

const comp = computed(() => resolveItemRenderer(props.item.itemType))

const compProps = computed(() => {
  if (props.item.itemType === 'tool_call') {
    return { call: props.item, result: props.pairResult }
  }
  if (props.item.itemType === 'card') {
    return { item: props.item, approval: props.approval }
  }
  return { item: props.item }
})
</script>
