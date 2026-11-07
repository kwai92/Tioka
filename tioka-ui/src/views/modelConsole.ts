/** FE-03：模型页 binding / 故障接力链 / 默认方案 — API 封装（供 Models.vue / vitest）。 */

export type ModelApi = <T = unknown>(path: string, opts?: RequestInit) => Promise<T>

export const BINDING_KINDS = ['chat', 'embedding', 'rerank'] as const
export type BindingKind = (typeof BINDING_KINDS)[number]

/** M1 控制台常用用途（对齐 ModelPurpose 子集）。 */
export const DEFAULT_PLAN_PURPOSES = [
  'CHAT',
  'FLOW',
  'EMBEDDING',
  'RERANK',
  'DECISION'
] as const
export type DefaultPlanPurpose = (typeof DEFAULT_PLAN_PURPOSES)[number]

export type BindingView = {
  id: string
  logicalModelId: string
  providerId: string
  modelName: string
  kind: string
  contextWindow: number | null
  embeddingDim: number | null
  inputPriceMicros: number
  outputPriceMicros: number
  rpmLimit: number | null
  tpmLimit: number | null
  enabled: boolean
}

export type UpdateBindingBody = {
  kind?: string
  embeddingDim?: number | null
  contextWindow?: number | null
  inputPriceMicros?: number
  outputPriceMicros?: number
  rpmLimit?: number | null
  tpmLimit?: number | null
  enabled?: boolean
}

export type CreateBindingBody = {
  logicalModelId: string
  providerId: string
  modelName: string
  kind?: BindingKind | string
  embeddingDim?: number | null
  contextWindow?: number | null
  inputPriceMicros?: number
  outputPriceMicros?: number
  rpmLimit?: number | null
  tpmLimit?: number | null
  enabled?: boolean
}

export type CreateRoutingPlanBody = {
  name: string
  type: string
  primaryBindingId: string
  fallbackBindingId?: string | null
  hardBindingId?: string | null
  fallbackChain?: string | null
}

export type RoutingPlanView = {
  id: string
  name: string
  type: string
  primaryBindingId: string
  fallbackBindingId: string | null
  hardBindingId: string | null
  published: boolean
  snapshotHash: string | null
  fallbackChain?: string | null
}

export type DefaultPlanView = {
  purpose: string
  routingPlanId: string | null
  bindingId: string | null
}

/** 有序 binding ID → 逗号链（去空、保序）。 */
export function joinFallbackChain(ids: string[]): string {
  return (ids || []).map((s) => String(s || '').trim()).filter(Boolean).join(',')
}

/** 逗号链 → 有序 ID 列表。 */
export function parseFallbackChain(chain: string | null | undefined): string[] {
  if (!chain || !String(chain).trim()) return []
  return String(chain)
    .split(',')
    .map((s) => s.trim())
    .filter(Boolean)
}

/** 预览：primary → fallback1 → … */
export function formatFailoverPreview(primaryId: string, chainIds: string[]): string {
  const parts = [primaryId, ...chainIds].map((s) => String(s || '').trim()).filter(Boolean)
  return parts.join(' → ')
}

export async function listBindings(api: ModelApi): Promise<BindingView[]> {
  return api('/api/model/bindings')
}

export async function createBinding(api: ModelApi, body: CreateBindingBody): Promise<string> {
  return api('/api/model/bindings', {
    method: 'POST',
    body: JSON.stringify(body)
  })
}

export async function patchBinding(
  api: ModelApi,
  id: string,
  body: UpdateBindingBody
): Promise<BindingView> {
  return api(`/api/model/bindings/${encodeURIComponent(id)}`, {
    method: 'PATCH',
    body: JSON.stringify(body)
  })
}

export async function createRoutingPlan(
  api: ModelApi,
  body: CreateRoutingPlanBody
): Promise<RoutingPlanView> {
  return api('/api/model/routing-plans', {
    method: 'POST',
    body: JSON.stringify(body)
  })
}

export async function listDefaultPlans(api: ModelApi): Promise<DefaultPlanView[]> {
  return api('/api/model/default-plans')
}

export async function putDefaultPlan(
  api: ModelApi,
  purpose: string,
  body: { routingPlanId?: string | null; bindingId?: string | null }
): Promise<DefaultPlanView> {
  return api(`/api/model/default-plans/${encodeURIComponent(purpose)}`, {
    method: 'PUT',
    body: JSON.stringify({
      routingPlanId: body.routingPlanId ?? null,
      bindingId: body.bindingId ?? null
    })
  })
}
