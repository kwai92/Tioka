/** HCI-04：Runtime item 渲染契约（对齐 runtime-event-v1 item）。 */

export type RuntimeItemView = {
  itemId: string
  seq: number
  step?: number
  itemType: string
  role?: string
  content: Record<string, unknown>
  pending?: boolean
}

export type ApprovalDecisionView = {
  approvalId: string
  status: string
  decidedBy?: string
  toolName?: string
}

export type ItemFixtureSample = {
  name: string
  item: RuntimeItemView
  /** 成对折叠时的 tool_result（仅 tool_call 样例用） */
  pair?: RuntimeItemView
  /** 已决定审批态（card 样例用） */
  approval?: ApprovalDecisionView
}
