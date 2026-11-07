import { describe, expect, it, vi } from 'vitest'
import { decideApproval } from './approvalApi'

describe('HCI-04 decideApproval', () => {
  it('POST /api/runtime/approvals/{id}/decision', async () => {
    const api = vi.fn().mockResolvedValue(null)
    await decideApproval(api, 'apv_1', {
      approved: true,
      answer: { args: { path: '/t' } },
      comment: 'ok'
    })
    expect(api).toHaveBeenCalledWith('/api/runtime/approvals/apv_1/decision', {
      method: 'POST',
      body: JSON.stringify({
        approved: true,
        answer: { args: { path: '/t' } },
        comment: 'ok'
      })
    })
  })
})
