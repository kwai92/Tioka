/** FE-02：AgentSpec 分区编辑器 — API 封装与发布错误定位（供 Agents.vue / vitest）。 */

export type AgentApi = <T = unknown>(path: string, opts?: RequestInit) => Promise<T>

export type PublishError = { path: string; code: string; message: string }

export type AgentSpec = {
  schemaVersion: number
  profile: {
    name: string
    description: string | null
    icon: string | null
    tags: string[]
    kind: 'CHAT' | 'TASK' | 'READONLY'
  }
  instructions: { sections: { key: string; text: string }[] }
  model: { routingPlanId: string; allowUserOverride: boolean }
  tools: {
    builtin: string[]
    mcp: { serverId: string; allowlist: string[] }[]
    flows: unknown[]
    business: unknown[]
  }
  skills: { skillVersionId: string; mode: 'ALWAYS' | 'MANUAL' }[]
  knowledge: { kbIds: string[]; mode: 'TOOL' | 'AUTO_RETRIEVE' }
  sandbox: { level: 'READ' | 'EDIT' | 'FULL'; networkAllowlist: string[] }
  approval: { toolPolicies: Record<string, 'auto' | 'approve' | 'deny'>; assignee: string | null }
  limits: { maxSteps: number; maxTokensPerTurn: number | null }
  charter: null
  output: null
  subAgents: null
  dataClearance: null
}

export type DraftView = { spec: AgentSpec; revision: number; baseVersionId: string | null }
export type PublishResult = { versionId: string; versionNo: number }
export type VersionView = {
  versionId: string
  versionNo: number
  status: string
  publishedAt: string | null
  publishedBy: string | null
  note: string | null
  current: boolean
  contentHash?: string
}

/** Tab name ↔ JSON Pointer 首段 */
export const EDITOR_SECTIONS = [
  'profile',
  'instructions',
  'model',
  'tools',
  'skills',
  'knowledge',
  'sandbox',
  'approval',
  'limits'
] as const

export type EditorSection = (typeof EDITOR_SECTIONS)[number]

export function sectionForPath(path: string): EditorSection | null {
  if (!path) return null
  const normalized = path.startsWith('/') ? path.slice(1) : path.replace(/^\$\.?/, '')
  const head = normalized.split(/[/.]/)[0]?.trim()
  if (!head) return null
  return (EDITOR_SECTIONS as readonly string[]).includes(head) ? (head as EditorSection) : null
}

/** 发布错误按 path 映射到分区（去重，保序）。 */
export function mapPublishErrorsToSections(errors: PublishError[]): EditorSection[] {
  const seen = new Set<EditorSection>()
  const out: EditorSection[] = []
  for (const e of errors || []) {
    const sec = sectionForPath(e.path)
    if (sec && !seen.has(sec)) {
      seen.add(sec)
      out.push(sec)
    }
  }
  return out
}

export function firstErrorPath(errors: PublishError[]): string | null {
  return errors?.[0]?.path ?? null
}

/** 提交前：固定 schemaVersion=1，预留字段强制 null，flows/business 置空数组。 */
export function sanitizeSpecForSave(spec: AgentSpec): AgentSpec {
  return {
    ...spec,
    schemaVersion: 1,
    tools: {
      ...spec.tools,
      flows: [],
      business: []
    },
    charter: null,
    output: null,
    subAgents: null,
    dataClearance: null
  }
}

export function extractPublishErrors(err: unknown): PublishError[] {
  const data = (err as { data?: { errors?: PublishError[] } })?.data
  const list = data?.errors
  return Array.isArray(list) ? list : []
}

export async function getDraft(api: AgentApi, agentId: string): Promise<DraftView> {
  return api(`/api/agents/${encodeURIComponent(agentId)}/draft`)
}

export async function saveDraft(
  api: AgentApi,
  agentId: string,
  spec: AgentSpec,
  revision: number
): Promise<DraftView> {
  return api(`/api/agents/${encodeURIComponent(agentId)}/draft`, {
    method: 'PUT',
    body: JSON.stringify({ spec: sanitizeSpecForSave(spec), revision })
  })
}

export async function validateAgent(
  api: AgentApi,
  agentId: string
): Promise<{ ok: boolean; errors: PublishError[] }> {
  return api(`/api/agents/${encodeURIComponent(agentId)}/validate`, { method: 'POST', body: '{}' })
}

export async function publishAgent(
  api: AgentApi,
  agentId: string,
  revision: number,
  note?: string
): Promise<PublishResult> {
  return api(`/api/agents/${encodeURIComponent(agentId)}/publish`, {
    method: 'POST',
    body: JSON.stringify({ revision, note: note || null })
  })
}

export async function listVersions(api: AgentApi, agentId: string): Promise<VersionView[]> {
  return api(`/api/agents/${encodeURIComponent(agentId)}/versions`)
}

export async function diffVersions(
  api: AgentApi,
  agentId: string,
  from: string,
  to: string
): Promise<Record<string, unknown>[]> {
  const q = new URLSearchParams({ from, to })
  return api(`/api/agents/${encodeURIComponent(agentId)}/versions/diff?${q}`)
}

export async function rollbackAgent(
  api: AgentApi,
  agentId: string,
  versionId: string
): Promise<PublishResult> {
  return api(`/api/agents/${encodeURIComponent(agentId)}/rollback`, {
    method: 'POST',
    body: JSON.stringify({ versionId })
  })
}
