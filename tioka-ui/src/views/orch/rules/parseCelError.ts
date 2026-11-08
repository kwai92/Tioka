/**
 * 解析后端 CEL 编译/校验错误中的行列（ORC-05 / FE-15 验收）。
 * 后端格式：`CEL 编译失败（行列 L:C）: msg` 或 `CEL 不得访问 event.body（行列 1:1）`
 */
export type CelErrorLocation = {
  line: number
  column: number
  message: string
  raw: string
}

const LOC_RE = /行列\s*(\d+)\s*:\s*(\d+)/

export function parseCelError(raw: string | null | undefined): CelErrorLocation | null {
  if (!raw) return null
  const m = LOC_RE.exec(raw)
  if (!m) return null
  const line = Math.max(1, Number(m[1]))
  const column = Math.max(1, Number(m[2]))
  let message = raw
  const colonIdx = raw.indexOf('）:')
  if (colonIdx >= 0) {
    message = raw.slice(colonIdx + 2).trim()
  } else {
    const half = raw.indexOf('）')
    if (half >= 0) message = raw.slice(0, half).replace(/（行列.*/, '').trim() || raw
  }
  return { line, column, message, raw }
}
