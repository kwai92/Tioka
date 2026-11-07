import { Ajv2020 } from 'ajv/dist/2020.js'

const ajv = new Ajv2020({ allErrors: true, strict: false })

/**
 * 提交前用工具 inputSchema（JSON Schema）校验编辑后的参数。
 * schema 缺省时只要求 args 为可解析对象。
 */
export function validateToolArgs(
  args: unknown,
  inputSchema: Record<string, unknown> | null | undefined
): { ok: true; args: Record<string, unknown> } | { ok: false; message: string } {
  if (args == null || typeof args !== 'object' || Array.isArray(args)) {
    return { ok: false, message: '参数须为 JSON 对象' }
  }
  const obj = args as Record<string, unknown>
  if (!inputSchema || Object.keys(inputSchema).length === 0) {
    return { ok: true, args: obj }
  }
  try {
    const validate = ajv.compile(inputSchema)
    if (!validate(obj)) {
      const detail = (validate.errors || [])
        .map((e) => `${e.instancePath || '/'} ${e.message || ''}`.trim())
        .join('; ')
      return { ok: false, message: detail || '参数不符合 inputSchema' }
    }
    return { ok: true, args: obj }
  } catch (e: unknown) {
    return { ok: false, message: e instanceof Error ? e.message : String(e) }
  }
}

/** 将 textarea 文本解析为对象；失败返回错误文案。 */
export function parseArgsJson(
  text: string
): { ok: true; args: Record<string, unknown> } | { ok: false; message: string } {
  try {
    const v = JSON.parse(text || '{}')
    if (v == null || typeof v !== 'object' || Array.isArray(v)) {
      return { ok: false, message: '参数须为 JSON 对象' }
    }
    return { ok: true, args: v as Record<string, unknown> }
  } catch (e: unknown) {
    return { ok: false, message: e instanceof Error ? e.message : 'JSON 解析失败' }
  }
}
