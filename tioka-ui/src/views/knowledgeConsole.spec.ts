import { describe, expect, it, vi } from 'vitest'
import {
  createKnowledgeBase,
  filterEmbeddingBindings,
  reindexPercent,
  type KnowledgeApi
} from './knowledgeConsole'

describe('FE-04 knowledgeConsole', () => {
  it('createKnowledgeBase body 含 embeddingBindingId', async () => {
    const api = vi.fn().mockResolvedValue({
      id: 'kb_1',
      name: 'demo',
      embeddingBindingId: 'bnd_e'
    }) as unknown as KnowledgeApi & ReturnType<typeof vi.fn>
    await createKnowledgeBase(api, { name: 'demo', embeddingBindingId: 'bnd_e' })
    expect(api).toHaveBeenCalledWith('/api/knowledge/bases', {
      method: 'POST',
      body: JSON.stringify({ name: 'demo', embeddingBindingId: 'bnd_e' })
    })
  })

  it('filterEmbeddingBindings 仅保留 embedding 且 enabled', () => {
    const out = filterEmbeddingBindings([
      { id: 'a', kind: 'embedding', enabled: true, embeddingDim: 1024, modelName: 'e1' },
      { id: 'b', kind: 'chat', enabled: true, embeddingDim: null },
      { id: 'c', kind: 'embedding', enabled: false, embeddingDim: 1024 },
      { id: 'd', kind: 'embedding', enabled: true, embeddingDim: 768 }
    ], { embeddingDim: 1024 })
    expect(out.map((x) => x.id)).toEqual(['a'])
    expect(out[0].label).toContain('e1')
  })

  it('reindexPercent：pending/total 与空库', () => {
    expect(reindexPercent({ pending: 2, total: 10 })).toBe(80)
    expect(reindexPercent({ pending: 0, total: 0 })).toBe(100)
    expect(reindexPercent({ pending: 5, total: 5 })).toBe(0)
  })
})
