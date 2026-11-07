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
import MessageItem from './MessageItem.vue'
import ReasoningItem from './ReasoningItem.vue'
import ToolResultItem from './ToolResultItem.vue'
import ToolPair from './ToolPair.vue'
import CitationItem from './CitationItem.vue'
import CardItem from './CardItem.vue'
import StatusItem from './StatusItem.vue'
import ErrorItem from './ErrorItem.vue'
import UnknownItem from './UnknownItem.vue'

const props = defineProps<{
  item: RuntimeItemView
  /** tool_call 成对折叠时传入对应 result */
  pairResult?: RuntimeItemView
  approval?: ApprovalDecisionView | null
}>()

const emit = defineEmits<{
  submitted: []
}>()

const comp = computed(() => {
  switch (props.item.itemType) {
    case 'message':
      return MessageItem
    case 'reasoning':
      return ReasoningItem
    case 'tool_call':
      return ToolPair
    case 'tool_result':
      return ToolResultItem
    case 'citation':
      return CitationItem
    case 'card':
      return CardItem
    case 'status':
      return StatusItem
    case 'error':
      return ErrorItem
    default:
      return UnknownItem
  }
})

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
