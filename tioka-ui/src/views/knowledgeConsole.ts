/** FE-04：知识台嵌入 binding 选择与 REINDEX_PENDING 进度 — API 封装（供 Knowledge.vue / vitest）。 */

export type KnowledgeApi = <T = unknown>(path: string, opts?: RequestInit) => Promise<T>

export type EmbeddingBindingView = {
  id: string
  embeddingDim: number | null
  enabled: boolean
  label: string
}

export type KnowledgeBaseView = {
  id: string
  workspaceId?: string
  name: string
  embeddingBindingId?: string | null
}

export type ReindexProgressView = {
  kbId: string
  pending: number
  ready: number
  failed: number
  embedding: number
  total: number
  percent: number
}

/** 候选过滤：仅 embedding + enabled；可选要求维度。 */
export function filterEmbeddingBindings(
  items: Array<{
    id: string
    kind?: string
    enabled?: boolean
    embeddingDim?: number | null
    label?: string
    modelName?: string | null
  }>,
  opts?: { embeddingDim?: number }
): EmbeddingBindingView[] {
  const wantDim = opts?.embeddingDim
  return (items || [])
    .filter((b) => String(b.kind || '').toLowerCase() === 'embedding')
    .filter((b) => b.enabled !== false)
    .filter((b) => wantDim == null || b.embeddingDim === wantDim)
    .map((b) => {
      const dim = b.embeddingDim ?? null
      const label =
        b.label ||
        (b.modelName
          ? `${b.modelName} (${b.id}, dim=${dim ?? '?'})`
          : `${b.id} (dim=${dim ?? '?'})`)
      return {
        id: b.id,
        embeddingDim: dim,
        enabled: b.enabled !== false,
        label
      }
    })
}

/** 进度百分比：total=0 → 100；否则 (total-pending)/total。 */
export function reindexPercent(input: { pending: number; total: number }): number {
  const total = Number(input.total) || 0
  const pending = Math.max(0, Number(input.pending) || 0)
  if (total <= 0) return 100
  return Math.round((100 * (total - pending)) / total)
}

export async function listEmbeddingBindings(api: KnowledgeApi): Promise<EmbeddingBindingView[]> {
  return api('/api/knowledge/embedding-bindings')
}

export async function createKnowledgeBase(
  api: KnowledgeApi,
  body: { name: string; embeddingBindingId: string; workspaceId?: string }
): Promise<KnowledgeBaseView> {
  return api('/api/knowledge/bases', {
    method: 'POST',
    body: JSON.stringify({
      name: body.name,
      embeddingBindingId: body.embeddingBindingId,
      ...(body.workspaceId ? { workspaceId: body.workspaceId } : {})
    })
  })
}

export async function fetchReindexProgress(
  api: KnowledgeApi,
  kbId: string
): Promise<ReindexProgressView> {
  return api(`/api/knowledge/bases/${encodeURIComponent(kbId)}/reindex-progress`)
}
