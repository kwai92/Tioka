import type { RuntimeItemView } from './types'

export type RenderUnit =
  | { kind: 'single'; item: RuntimeItemView }
  | { kind: 'tool_pair'; call: RuntimeItemView; result?: RuntimeItemView }

/**
 * 将扁平 item 列表按 callId 把 tool_call / tool_result 收成成对折叠单元。
 * 无配对的 tool_result 仍单独渲染。
 */
export function groupItemsForRender(items: RuntimeItemView[]): RenderUnit[] {
  const usedResultIds = new Set<string>()
  const resultByCallId = new Map<string, RuntimeItemView>()
  for (const it of items) {
    if (it.itemType !== 'tool_result') continue
    const callId = typeof it.content.callId === 'string' ? it.content.callId : ''
    if (callId && !resultByCallId.has(callId)) {
      resultByCallId.set(callId, it)
    }
  }

  const out: RenderUnit[] = []
  for (const it of items) {
    if (it.itemType === 'tool_result' && usedResultIds.has(it.itemId)) {
      continue
    }
    if (it.itemType === 'tool_call') {
      const callId = typeof it.content.callId === 'string' ? it.content.callId : ''
      const result = callId ? resultByCallId.get(callId) : undefined
      if (result) usedResultIds.add(result.itemId)
      out.push({ kind: 'tool_pair', call: it, result })
      continue
    }
    if (it.itemType === 'tool_result') {
      out.push({ kind: 'single', item: it })
      continue
    }
    out.push({ kind: 'single', item: it })
  }
  return out
}
