/** Chat UX 守卫与展示辅助（HCI-02 / CH-01）。 */

export type SendGateInput = {
  text: string
  activeId: string
  /** 正在发送或 Turn 进行中时禁止重复提交 */
  sending?: boolean
}

export function gateSend(input: SendGateInput): string | null {
  const text = (input.text || '').trim()
  if (!text) return '消息不能为空'
  if (!input.activeId) return '请先创建或选择会话'
  if (input.sending) return '请等待当前回复完成后再发送'
  return null
}

/** @deprecated HCI-02 改由 thread store / turn 终态控制；保留供旧测试兼容。 */
export function shouldAppendStreamPart(cancelled: boolean): boolean {
  return !cancelled
}

export function sidebarIsProductCost(asideTitle: string): boolean {
  return asideTitle.includes('成本') || asideTitle.toLowerCase().includes('cost')
}

/** item → PartRenderer 可消费的 part（HCI-04 前过渡）。 */
export function itemToPart(item: {
  itemType: string
  role?: string
  content: Record<string, unknown>
  pending?: boolean
}) {
  const type = item.itemType === 'message' ? 'text' : item.itemType
  const text =
    typeof item.content.text === 'string'
      ? item.content.text
      : typeof item.content.reasoning === 'string'
        ? item.content.reasoning
        : typeof item.content.output === 'string'
          ? item.content.output
          : ''
  return {
    type,
    text: item.pending && !text ? '发送中…' : text,
    payload: { ...item.content, pending: item.pending, role: item.role }
  }
}
