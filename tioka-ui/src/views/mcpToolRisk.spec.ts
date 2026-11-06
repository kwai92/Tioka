import { describe, expect, it, vi } from 'vitest'
import { patchToolRisk } from './mcpToolRisk'

describe('FE-01 patchToolRisk', () => {
  it('改 risk 调 PATCH API', async () => {
    const api = vi.fn().mockResolvedValue({ name: 'mcp_echo', risk: 'READ' })
    const out = await patchToolRisk(api, 'mcp_1', 'mcp_echo', 'READ')
    expect(out.risk).toBe('READ')
    expect(api).toHaveBeenCalledTimes(1)
    expect(api).toHaveBeenCalledWith('/api/mcp/servers/mcp_1/tools/mcp_echo', {
      method: 'PATCH',
      body: JSON.stringify({ risk: 'READ' })
    })
  })
})
