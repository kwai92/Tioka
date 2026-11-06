import { api } from './http'

/**
 * HCI-02 / CHT-02 对话异步客户端。
 *
 * - POST /api/chat/conversations/{id}/messages → 202 {turnId, threadId, eventsUrl}
 * - GET  /api/chat/conversations/{id}/messages?afterSeq → ItemView[]
 * - POST /api/chat/conversations/{id}/stop
 */

export type TurnAccepted = {
  turnId: string
  threadId: string
  eventsUrl: string
  status?: string
}

export type RuntimeItemView = {
  itemId: string
  threadId?: string
  turnId?: string
  seq: number
  step?: number
  type?: string
  role?: string
  content?: Record<string, unknown>
}

export async function listConversationItems(
  conversationId: string,
  afterSeq = 0,
  limit = 200
): Promise<RuntimeItemView[]> {
  return api<RuntimeItemView[]>(
    `/api/chat/conversations/${conversationId}/messages?afterSeq=${afterSeq}&limit=${limit}`
  )
}

export async function startChatTurn(opts: {
  conversationId: string
  text: string
  clientMsgId: string
  /** 已忽略：threadId 由服务端会话绑定 */
  threadId?: string
}): Promise<TurnAccepted> {
  const handle = await api<TurnAccepted>(`/api/chat/conversations/${opts.conversationId}/messages`, {
    method: 'POST',
    body: JSON.stringify({
      text: opts.text,
      clientMsgId: opts.clientMsgId
    })
  })
  return {
    turnId: handle.turnId,
    threadId: handle.threadId,
    eventsUrl: handle.eventsUrl || `/api/runtime/threads/${handle.threadId}/events`,
    status: handle.status
  }
}

/** 停止当前活动 Turn（服务端查找活动 Turn 并 interrupt）。 */
export async function stopActiveTurn(conversationId: string): Promise<void> {
  await api(`/api/chat/conversations/${conversationId}/stop`, {
    method: 'POST',
    body: '{}'
  })
}
