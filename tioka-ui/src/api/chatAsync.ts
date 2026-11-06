import { api } from './http'

/**
 * HCI-02 对话异步客户端。
 *
 * CHT-02（W8–W9）契约：
 * - POST /api/chat/conversations/{id}/messages → 202 {turnId, threadId, eventsUrl}
 * - GET  /api/chat/conversations/{id}/messages?afterSeq → ItemView[]
 * - POST /api/chat/conversations/{id}/stop
 *
 * 当前后端尚未落地 CHT-02：本模块经已存在的 RuntimeApi 实现同等异步语义，
 * 会话侧仍用 conversation.runtimeSessionId 作为 threadId。
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

export async function listThreadItems(
  threadId: string,
  afterSeq = 0,
  limit = 200
): Promise<RuntimeItemView[]> {
  return api<RuntimeItemView[]>(
    `/api/runtime/threads/${threadId}/items?afterSeq=${afterSeq}&limit=${limit}`
  )
}

export async function startChatTurn(opts: {
  conversationId: string
  threadId: string
  text: string
  clientMsgId: string
}): Promise<TurnAccepted> {
  const handle = await api<{
    turnId: string
    status?: string
    eventsUrl: string
  }>(`/api/runtime/threads/${opts.threadId}/turns`, {
    method: 'POST',
    body: JSON.stringify({
      clientMsgId: opts.clientMsgId,
      input: [{ type: 'message', text: opts.text }],
      origin: 'chat',
      originRef: opts.conversationId
    })
  })
  return {
    turnId: handle.turnId,
    threadId: opts.threadId,
    eventsUrl: handle.eventsUrl || `/api/runtime/threads/${opts.threadId}/events`,
    status: handle.status
  }
}

/** 停止当前 Turn；对齐 CHT-02 `/stop` → interrupt。 */
export async function stopActiveTurn(turnId: string): Promise<void> {
  await api(`/api/runtime/turns/${turnId}/interrupt`, {
    method: 'POST',
    body: '{}'
  })
}
