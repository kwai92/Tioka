/** RT-07 审批提交封装（HCI-04 CardItem）。 */

export type DecideApprovalBody = {
  approved: boolean
  answer?: { args?: Record<string, unknown> }
  comment?: string
}

export type ApiFn = (path: string, init?: RequestInit) => Promise<unknown>

export async function decideApproval(
  api: ApiFn,
  approvalId: string,
  body: DecideApprovalBody
): Promise<void> {
  await api(`/api/runtime/approvals/${encodeURIComponent(approvalId)}/decision`, {
    method: 'POST',
    body: JSON.stringify(body)
  })
}

export type ApprovalRemoteView = {
  id: string
  status: string
  decidedBy?: string | null
  toolName?: string | null
  args?: Record<string, unknown>
  comment?: string | null
}

export async function getApproval(api: ApiFn, approvalId: string): Promise<ApprovalRemoteView> {
  return (await api(`/api/runtime/approvals/${encodeURIComponent(approvalId)}`)) as ApprovalRemoteView
}
