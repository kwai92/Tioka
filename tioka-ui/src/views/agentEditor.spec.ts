import { describe, expect, it, vi } from 'vitest'
import {
  firstErrorPath,
  mapPublishErrorsToSections,
  rollbackAgent,
  saveDraft,
  type AgentSpec
} from './agentEditor'

const sampleSpec: AgentSpec = {
  schemaVersion: 1,
  profile: { name: 'a', description: null, icon: null, tags: [], kind: 'CHAT' },
  instructions: { sections: [{ key: 'sys', text: 'hi' }] },
  model: { routingPlanId: 'rp1', allowUserOverride: false },
  tools: { builtin: [], mcp: [], flows: [], business: [] },
  skills: [],
  knowledge: { kbIds: [], mode: 'TOOL' },
  sandbox: { level: 'EDIT', networkAllowlist: [] },
  approval: { toolPolicies: {}, assignee: null },
  limits: { maxSteps: 20, maxTokensPerTurn: null },
  charter: null,
  output: null,
  subAgents: null,
  dataClearance: null
}

describe('FE-02 agentEditor', () => {
  it('保存草稿调 PUT /draft 并回写 revision', async () => {
    const api = vi.fn().mockResolvedValue({
      spec: sampleSpec,
      revision: 2,
      baseVersionId: null
    })
    const out = await saveDraft(api, 'agt_1', sampleSpec, 1)
    expect(out.revision).toBe(2)
    expect(api).toHaveBeenCalledTimes(1)
    expect(api).toHaveBeenCalledWith('/api/agents/agt_1/draft', {
      method: 'PUT',
      body: JSON.stringify({ spec: sampleSpec, revision: 1 })
    })
  })

  it('发布失败多条错误定位到不同分区', () => {
    const errors = [
      { path: '/model/routingPlanId', code: 'REF_NOT_FOUND', message: '缺路由' },
      { path: '/tools/mcp/0/serverId', code: 'REF_FORBIDDEN', message: '无权 MCP' }
    ]
    expect(mapPublishErrorsToSections(errors)).toEqual(['model', 'tools'])
    expect(firstErrorPath(errors)).toBe('/model/routingPlanId')
  })

  it('回滚调 POST /rollback', async () => {
    const api = vi.fn().mockResolvedValue({ versionId: 'av_old', versionNo: 1 })
    const out = await rollbackAgent(api, 'agt_1', 'av_old')
    expect(out.versionId).toBe('av_old')
    expect(api).toHaveBeenCalledWith('/api/agents/agt_1/rollback', {
      method: 'POST',
      body: JSON.stringify({ versionId: 'av_old' })
    })
  })
})
