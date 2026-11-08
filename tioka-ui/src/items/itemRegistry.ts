/**
 * F26 / F9：轨迹与会话 Item 渲染注册表。
 * `data` 由 P3-00c 登记 DataItem（SCH-04 schema 注册点在 dataItemRegistry）。
 * 其余未知类型回落到 UnknownItem，不抛错。
 */
import type { Component } from 'vue'
import MessageItem from './MessageItem.vue'
import ReasoningItem from './ReasoningItem.vue'
import ToolPair from './ToolPair.vue'
import ToolResultItem from './ToolResultItem.vue'
import CitationItem from './CitationItem.vue'
import CardItem from './CardItem.vue'
import StatusItem from './StatusItem.vue'
import ErrorItem from './ErrorItem.vue'
import DataItem from './DataItem.vue'
import UnknownItem from './UnknownItem.vue'

export const itemRenderers: Record<string, Component> = {
  message: MessageItem,
  reasoning: ReasoningItem,
  tool_call: ToolPair,
  tool_result: ToolResultItem,
  citation: CitationItem,
  card: CardItem,
  status: StatusItem,
  error: ErrorItem,
  data: DataItem
}

export function resolveItemRenderer(itemType: string | undefined | null): Component {
  if (!itemType) return UnknownItem
  return itemRenderers[itemType] ?? UnknownItem
}

export function registerItemRenderer(itemType: string, component: Component): void {
  itemRenderers[itemType] = component
}

export { UnknownItem }
