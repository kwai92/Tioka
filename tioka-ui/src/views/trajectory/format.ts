/** 轨迹视图展示格式化 */

export function formatDurationMs(ms: number | null | undefined): string {
  if (ms == null || Number.isNaN(ms)) return '—'
  if (ms < 1000) return `${Math.round(ms)}ms`
  return `${(ms / 1000).toFixed(1)}s`
}

export function formatCostMicros(micros: number | null | undefined): string {
  if (micros == null) return '—'
  if (micros < 1000) return `${micros}µ$`
  return `$${(micros / 1_000_000).toFixed(4)}`
}

export function formatTokens(
  prompt: number | null | undefined,
  completion: number | null | undefined
): string {
  const p = prompt ?? 0
  const c = completion ?? 0
  return `${p}+${c} tokens`
}

export function elapsedMs(
  startedAt: string | null | undefined,
  endedAt: string | null | undefined
): number | null {
  if (!startedAt || !endedAt) return null
  const a = Date.parse(startedAt)
  const b = Date.parse(endedAt)
  if (Number.isNaN(a) || Number.isNaN(b)) return null
  return Math.max(0, b - a)
}

export function timelineHref(requestId: string | null | undefined): string | null {
  if (!requestId) return null
  return `/observability/timeline?requestId=${encodeURIComponent(requestId)}`
}
