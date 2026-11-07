/** HCI-02：thread 事件归并（纯函数，便于 vitest）。 */

export type ThreadItem = {
  itemId: string
  seq: number
  step?: number
  itemType: string
  role?: string
  content: Record<string, unknown>
  /** 本地乐观插入，尚未被服务端 item 替换 */
  pending?: boolean
  clientMsgId?: string
}

export type ActiveTurn = {
  turnId: string
  status: string
} | null

export type ApprovalState = {
  approvalId: string
  status: string
  decidedBy?: string
  toolName?: string
}

export type ThreadState = {
  items: Map<string, ThreadItem>
  activeTurn: ActiveTurn
  lastEventId: string
  /** 一次性提示：模型切换 / discarded */
  discardNotice: string | null
  reconnecting: boolean
  /** approvalId → 最新审批态（HCI-04） */
  approvals: Map<string, ApprovalState>
}

export type RuntimeEvent = {
  type?: string
  turnId?: string
  item?: {
    itemId?: string
    seq?: number
    step?: number
    itemType?: string
    role?: string
    content?: Record<string, unknown>
  }
  delta?: {
    itemId?: string
    index?: number
    text?: string
    reasoning?: string
  }
  turn?: {
    status?: string
  }
  approval?: {
    approvalId?: string
    status?: string
    toolName?: string
    decidedBy?: string
  }
  [key: string]: unknown
}

export type ItemView = {
  itemId: string
  threadId?: string
  turnId?: string
  seq: number
  step?: number
  type?: string
  itemType?: string
  role?: string
  content?: Record<string, unknown>
}

export function createThreadState(): ThreadState {
  return {
    items: new Map(),
    activeTurn: null,
    lastEventId: '',
    discardNotice: null,
    reconnecting: false,
    approvals: new Map()
  }
}

export function sortedItems(state: ThreadState): ThreadItem[] {
  return [...state.items.values()].sort((a, b) => {
    if (a.pending && !b.pending) return 1
    if (!a.pending && b.pending) return -1
    if (a.seq !== b.seq) return a.seq - b.seq
    return a.itemId.localeCompare(b.itemId)
  })
}

export function maxSeq(state: ThreadState): number {
  let max = 0
  for (const it of state.items.values()) {
    if (!it.pending && it.seq > max) max = it.seq
  }
  return max
}

export function seedFromItems(state: ThreadState, views: ItemView[]): ThreadState {
  const items = new Map(state.items)
  for (const v of views) {
    const itemType = v.itemType || v.type || 'message'
    const itemId = v.itemId
    if (!itemId) continue
    items.set(itemId, {
      itemId,
      seq: v.seq,
      step: v.step,
      itemType,
      role: v.role,
      content: v.content ?? {}
    })
  }
  // 用服务端同文案用户消息替换乐观项
  for (const [id, it] of [...items.entries()]) {
    if (!it.pending || it.role !== 'user') continue
    const text = String(it.content.text ?? '')
    for (const other of items.values()) {
      if (other.pending || other.role !== 'user') continue
      if (String(other.content.text ?? '') === text) {
        items.delete(id)
        break
      }
    }
  }
  const next = { ...state, items }
  const seq = maxSeq(next)
  return {
    ...next,
    lastEventId: seq > 0 ? String(seq) : state.lastEventId
  }
}

export function insertOptimisticUser(
  state: ThreadState,
  clientMsgId: string,
  text: string
): ThreadState {
  const items = new Map(state.items)
  const itemId = `optimistic:${clientMsgId}`
  items.set(itemId, {
    itemId,
    seq: Number.MAX_SAFE_INTEGER,
    itemType: 'message',
    role: 'user',
    content: { text },
    pending: true,
    clientMsgId
  })
  return { ...state, items }
}

/** 用服务端 user item 替换乐观消息（按 clientMsgId 或文案），不重复插入。 */
export function replaceOptimisticWithServerUser(
  state: ThreadState,
  server: ThreadItem
): ThreadState {
  const items = new Map(state.items)
  const serverText = String(server.content.text ?? '')
  for (const [id, it] of [...items.entries()]) {
    if (!it.pending || it.role !== 'user') continue
    const sameClient =
      it.clientMsgId && server.clientMsgId && it.clientMsgId === server.clientMsgId
    const sameText = String(it.content.text ?? '') === serverText
    if (sameClient || sameText) {
      items.delete(id)
    }
  }
  items.set(server.itemId, { ...server, pending: false })
  return { ...state, items }
}

export function applyRuntimeEvent(
  state: ThreadState,
  ev: RuntimeEvent,
  eventId?: string
): ThreadState {
  let next: ThreadState = state
  if (eventId) {
    next = { ...next, lastEventId: eventId }
  }

  const type = ev.type || ''
  if (type.startsWith('turn.')) {
    const status = ev.turn?.status || type.slice('turn.'.length).toUpperCase()
    const turnId = ev.turnId || next.activeTurn?.turnId || ''
    const terminal = ['COMPLETED', 'FAILED', 'CANCELLED'].includes(status)
    return {
      ...next,
      activeTurn: terminal ? null : turnId ? { turnId, status } : next.activeTurn
    }
  }

  if (type === 'item.created' && ev.item?.itemId) {
    const items = new Map(next.items)
    const existing = items.get(ev.item.itemId)
    items.set(ev.item.itemId, {
      itemId: ev.item.itemId,
      seq: ev.item.seq ?? existing?.seq ?? 0,
      step: ev.item.step ?? existing?.step,
      itemType: ev.item.itemType || existing?.itemType || 'message',
      role: ev.item.role || existing?.role || 'assistant',
      content: { ...(existing?.content ?? {}), ...(ev.item.content ?? {}) }
    })
    return { ...next, items }
  }

  if (type === 'item.delta' && ev.delta?.itemId) {
    const items = new Map(next.items)
    const cur = items.get(ev.delta.itemId) || {
      itemId: ev.delta.itemId,
      seq: 0,
      itemType: 'message',
      role: 'assistant',
      content: {}
    }
    const content = { ...cur.content }
    if (ev.delta.text) {
      content.text = String(content.text ?? '') + ev.delta.text
    }
    if (ev.delta.reasoning) {
      content.reasoning = String(content.reasoning ?? '') + ev.delta.reasoning
    }
    items.set(ev.delta.itemId, { ...cur, content })
    return { ...next, items }
  }

  if (type === 'item.completed' && ev.item?.itemId) {
    const server: ThreadItem = {
      itemId: ev.item.itemId,
      seq: ev.item.seq ?? 0,
      step: ev.item.step,
      itemType: ev.item.itemType || 'message',
      role: ev.item.role,
      content: ev.item.content ?? {}
    }
    if (server.role === 'user') {
      return replaceOptimisticWithServerUser(next, server)
    }
    const items = new Map(next.items)
    items.set(server.itemId, server)
    return { ...next, items }
  }

  if (type === 'item.discarded' && ev.item?.itemId) {
    const items = new Map(next.items)
    items.delete(ev.item.itemId)
    return {
      ...next,
      items,
      discardNotice: next.discardNotice ?? '模型已切换，正在重新生成'
    }
  }

  if (type === 'approval.requested' || type === 'approval.decided') {
    const a = ev.approval
    const approvalId = a?.approvalId
    if (!approvalId) return next
    const approvals = new Map(next.approvals)
    const prev = approvals.get(approvalId)
    approvals.set(approvalId, {
      approvalId,
      status: a?.status || prev?.status || (type === 'approval.requested' ? 'PENDING' : 'PENDING'),
      decidedBy: a?.decidedBy || prev?.decidedBy,
      toolName: a?.toolName || prev?.toolName
    })
    return { ...next, approvals }
  }

  return next
}

export function clearDiscardNotice(state: ThreadState): ThreadState {
  return { ...state, discardNotice: null }
}

export function setReconnecting(state: ThreadState, reconnecting: boolean): ThreadState {
  return { ...state, reconnecting }
}

export function itemDisplayText(item: ThreadItem): string {
  const c = item.content
  if (typeof c.text === 'string') return c.text
  if (typeof c.reasoning === 'string') return c.reasoning
  if (typeof c.output === 'string') return c.output
  return ''
}
