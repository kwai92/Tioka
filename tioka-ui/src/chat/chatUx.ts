/** Chat CH-01：可单测的发送/取消守卫（不含网络）。 */

export type SendGateInput = {
  text: string
  activeId: string
  cancelled: boolean
}

export function gateSend(input: SendGateInput): string | null {
  const text = (input.text || '').trim()
  if (!text) return '消息不能为空'
  if (!input.activeId) return '请先创建或选择会话'
  if (input.cancelled) return '会话已取消，请新建会话后再发送'
  return null
}

export function shouldAppendStreamPart(cancelled: boolean): boolean {
  return !cancelled
}

export function sidebarIsProductCost(asideTitle: string): boolean {
  return asideTitle.includes('成本') || asideTitle.toLowerCase().includes('cost')
}
