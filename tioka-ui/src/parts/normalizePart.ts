/** Part 归一化：保证 PartRenderer 始终拿到稳定结构 */

export type PartInput = {
  type?: string
  text?: string
  payload?: Record<string, unknown>
  id?: string
  [key: string]: unknown
}

export type NormalizedPart = {
  type: string
  text: string
  payload: Record<string, unknown>
  id?: string
}

export function normalizePart(part: PartInput | null | undefined): NormalizedPart {
  if (!part || typeof part !== 'object') {
    return { type: 'unknown', text: '', payload: {} }
  }
  const type = typeof part.type === 'string' && part.type ? part.type : 'unknown'
  const text = typeof part.text === 'string' ? part.text : ''
  const payload =
    part.payload && typeof part.payload === 'object' && !Array.isArray(part.payload)
      ? (part.payload as Record<string, unknown>)
      : {}
  return {
    type,
    text,
    payload,
    id: typeof part.id === 'string' ? part.id : undefined
  }
}

/** 将流式 text delta 拼接到已有 parts 列表（原地不可变返回） */
export function appendStreamPart(parts: PartInput[], incoming: PartInput): PartInput[] {
  const n = normalizePart(incoming)
  const isDelta = n.type === 'text' && !!n.payload.delta
  if (isDelta) {
    const last = parts[parts.length - 1]
    if (last && normalizePart(last).type === 'text') {
      const merged = [...parts]
      merged[merged.length - 1] = {
        ...last,
        type: 'text',
        text: (last.text || '') + n.text
      }
      return merged
    }
    return [...parts, { type: 'text', text: n.text, payload: {} }]
  }
  return [...parts, { type: n.type, text: n.text, payload: n.payload, id: n.id }]
}

/** citation Part 字段对齐：payload.citationId / title / snippet */
export function normalizeCitationPart(part: PartInput): PartInput {
  const n = normalizePart(part)
  const p = n.payload
  const citationId = String(
    p.citationId ?? p.id ?? (typeof part.citationId === 'string' ? part.citationId : '') ?? ''
  )
  const title = String(p.title ?? '')
  const snippet = String(p.snippet ?? p.text ?? n.text ?? '')
  return {
    type: 'citation',
    text: snippet,
    payload: {
      ...p,
      citationId,
      title,
      snippet
    },
    id: n.id || citationId || undefined
  }
}

type HitLike = Record<string, unknown>

function hitToCitationPart(h: HitLike): PartInput | null {
  const citationId = String(h.citationId ?? h.id ?? '')
  if (!citationId) return null
  const title = String(h.title ?? '')
  const snippet = String(h.text ?? h.snippet ?? '')
  return {
    type: 'citation',
    text: snippet,
    payload: {
      citationId,
      title,
      snippet,
      docId: h.docId,
      chunkId: h.chunkId,
      score: h.score,
      ordinal: h.ordinal
    },
    id: citationId
  }
}

/** 从 kb.search JSON（或含 hits 的对象 / Java toString）尽量抽出 citation 气泡 */
export function tryParseKbSearchCitations(raw: string): PartInput[] | null {
  if (!raw || !String(raw).trim()) return null
  const text = String(raw).trim()

  let data: unknown
  try {
    data = JSON.parse(text)
  } catch {
    return tryParseLooseCitations(text)
  }

  let hits: unknown[] | null = null
  if (Array.isArray(data)) {
    hits = data
  } else if (data && typeof data === 'object') {
    const obj = data as Record<string, unknown>
    if (Array.isArray(obj.hits)) hits = obj.hits
    else if (obj.citationId || obj.id) hits = [obj]
  }
  if (!hits || !hits.length) return null

  const parts: PartInput[] = []
  for (const item of hits) {
    if (!item || typeof item !== 'object') continue
    const c = hitToCitationPart(item as HitLike)
    if (c) parts.push(c)
  }
  return parts.length ? parts : null
}

/** 容错：Java record toString / 杂文本中抓 citationId */
function tryParseLooseCitations(text: string): PartInput[] | null {
  const ids = [...text.matchAll(/citationId[=:]["']?([A-Za-z0-9_-]+)/g)].map((m) => m[1])
  const unique = [...new Set(ids)]
  if (!unique.length) return null
  return unique.map((citationId) => {
    const block = text.includes(citationId)
      ? text.slice(Math.max(0, text.indexOf(citationId) - 120), text.indexOf(citationId) + 160)
      : ''
    const titleMatch = block.match(/title[=:]["']?([^,\]]+)/)
    const textMatch = block.match(/text[=:]["']?([^,\]]+)/)
    return {
      type: 'citation',
      text: textMatch?.[1]?.trim() || '',
      payload: {
        citationId,
        title: titleMatch?.[1]?.trim() || '',
        snippet: textMatch?.[1]?.trim() || ''
      },
      id: citationId
    }
  })
}

/**
 * 展示前展开：原生 citation 对齐字段；
 * tool_result 若像 kb.search 结果则拆成 citation 气泡，否则原样保留。
 */
export function expandPartsForDisplay(parts: PartInput[] | null | undefined): PartInput[] {
  if (!parts || !parts.length) return []
  const out: PartInput[] = []
  for (const raw of parts) {
    const n = normalizePart(raw)
    if (n.type === 'citation') {
      out.push(normalizeCitationPart(raw))
      continue
    }
    if (n.type === 'tool_result') {
      const body =
        n.text ||
        (Object.keys(n.payload).length ? JSON.stringify(n.payload) : '')
      const citations = tryParseKbSearchCitations(body)
      if (citations && citations.length) {
        out.push(...citations)
        continue
      }
    }
    out.push({
      type: n.type,
      text: n.text,
      payload: n.payload,
      id: n.id
    })
  }
  return out
}
