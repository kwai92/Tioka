/** FE-06：Flow LLM 节点改选路由方案；旧直连字段剥离（对齐 FLW-02 migrator）。 */

/** 与后端 `LlmNodeConfigMigrator.LEGACY_KEYS` 对齐（小写比较）。 */
export const LLM_LEGACY_DIRECT_KEYS = [
  'baseUrl',
  'apiKey',
  'apiKeyRef',
  'model',
  'modelName',
  'modelRef'
] as const

const LEGACY_LOWER = new Set(LLM_LEGACY_DIRECT_KEYS.map((k) => k.toLowerCase()))

export type LlmNodeConfig = Record<string, unknown>

export function isLegacyLlmDirectKey(key: string): boolean {
  return LEGACY_LOWER.has(String(key).toLowerCase())
}

/** 是否含旧直连字段（baseUrl / apiKey / model 等）。 */
export function hasLegacyLlmDirectFields(config: LlmNodeConfig | null | undefined): boolean {
  if (!config || typeof config !== 'object') return false
  return Object.keys(config).some(isLegacyLlmDirectKey)
}

/** 取出 legacy 键值（保留原始键名），供只读提示。 */
export function extractLegacyLlmDirectFields(
  config: LlmNodeConfig | null | undefined
): Record<string, unknown> {
  const out: Record<string, unknown> = {}
  if (!config || typeof config !== 'object') return out
  for (const [k, v] of Object.entries(config)) {
    if (isLegacyLlmDirectKey(k)) out[k] = v
  }
  return out
}

/**
 * 规范化 LLM 节点 config：剥离 legacy 直连键；空白 `routingPlanId` 去掉（空 = FLOW 默认）。
 * 不修改入参。
 */
export function normalizeLlmNodeConfig(config: LlmNodeConfig | null | undefined): LlmNodeConfig {
  const out: LlmNodeConfig = {}
  if (!config || typeof config !== 'object') return out
  for (const [k, v] of Object.entries(config)) {
    if (isLegacyLlmDirectKey(k)) continue
    if (k === 'routingPlanId') {
      if (v == null) continue
      const s = String(v).trim()
      if (!s) continue
      out.routingPlanId = s
      continue
    }
    out[k] = v
  }
  return out
}

export type PublishedPlanOption = { id: string; name: string; type?: string; published?: boolean }

/** 下拉仅已发布方案。 */
export function filterPublishedRoutingPlans<T extends PublishedPlanOption>(plans: T[]): T[] {
  return (plans || []).filter((p) => p && p.published === true && p.id)
}
