#!/usr/bin/env node
/**
 * HCI-04：从 runtime-event-v1.json 的 itemType.examples 生成/校验
 * tioka-ui/src/items/__fixtures__/{type}.json。
 *
 * 用法：
 *   node scripts/gen-item-fixtures.mjs          # 补齐缺失样例（不覆盖已有）
 *   node scripts/gen-item-fixtures.mjs --check  # CI：缺类型或不足 2 样例则失败
 *
 * 新类型（如 data、change_set）只生成 UnknownItem 可用样例，不抛错（M1-00 F9）。
 */
import { readFileSync, writeFileSync, mkdirSync, existsSync, readdirSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = join(dirname(fileURLToPath(import.meta.url)), '..')
const schemaPath = join(
  root,
  'tioka-modules/tioka-module-runtime/tioka-module-runtime-api/src/main/resources/schema/runtime-event-v1.json'
)
const fixturesDir = join(root, 'tioka-ui/src/items/__fixtures__')
const checkOnly = process.argv.includes('--check')

const schema = JSON.parse(readFileSync(schemaPath, 'utf8'))
const examples = schema?.$defs?.item?.properties?.itemType?.examples
if (!Array.isArray(examples) || examples.length === 0) {
  console.error('runtime-event-v1.json 缺少 $defs.item.properties.itemType.examples')
  process.exit(1)
}

mkdirSync(fixturesDir, { recursive: true })

function sample(type, name, content, extra = {}) {
  return {
    name,
    item: {
      itemId: `fix_${type}_${name}`,
      seq: 1,
      itemType: type,
      role: roleFor(type),
      content,
      ...extra
    }
  }
}

function roleFor(type) {
  if (type === 'message') return 'assistant'
  if (type === 'tool_result') return 'tool'
  if (type === 'status' || type === 'error' || type === 'card') return 'system'
  return 'assistant'
}

function defaultSamples(type) {
  switch (type) {
    case 'message':
      return [
        sample(type, 'basic', { text: '你好，Tioka' }),
        sample(type, 'long-text', {
          text: '超长文本\n'.repeat(40) + 'END'
        })
      ]
    case 'reasoning':
      return [
        sample(type, 'basic', { reasoning: '先检索再回答' }),
        sample(type, 'empty', { reasoning: '' })
      ]
    case 'tool_call':
      return [
        {
          ...sample(type, 'basic', {
            callId: 'c1',
            name: 'echo',
            arguments: { text: 'hi' }
          }),
          pair: {
            itemId: 'fx_tool_result_pair',
            seq: 2,
            itemType: 'tool_result',
            role: 'tool',
            content: { callId: 'c1', name: 'echo', ok: true, output: 'hi' }
          }
        },
        sample(type, 'pending', { callId: 'c2', name: 'kb.search', arguments: { q: 'x' } })
      ]
    case 'tool_result':
      return [
        sample(type, 'ok', { callId: 'c1', name: 'echo', ok: true, output: 'ok' }),
        sample(type, 'truncated', {
          callId: 'c9',
          name: 'kb.search',
          ok: true,
          output: 'TRUNCATED:' + 'x'.repeat(500)
        })
      ]
    case 'citation':
      return [
        sample(type, 'basic', {
          citationId: 'cit_1',
          title: '手册',
          snippet: '引用片段'
        }),
        sample(type, 'no-id', { title: '无 id', snippet: '…' })
      ]
    case 'card':
      return [
        sample(type, 'tool-pending', {
          kind: 'tool',
          approvalId: 'apv_1',
          toolName: 'fs.write',
          risk: 'WRITE',
          reason: '写入需审批',
          args: { path: '/tmp/a', body: 'x' },
          inputSchema: {
            type: 'object',
            properties: { path: { type: 'string' }, body: { type: 'string' } },
            required: ['path', 'body'],
            additionalProperties: false
          }
        }),
        {
          ...sample(type, 'tool-decided', {
            kind: 'tool',
            approvalId: 'apv_2',
            toolName: 'fs.write',
            risk: 'DESTRUCTIVE',
            reason: '已处理',
            args: { path: '/tmp/b' }
          }),
          approval: {
            approvalId: 'apv_2',
            status: 'APPROVED',
            decidedBy: 'user_alice'
          }
        }
      ]
    case 'status':
      return [
        sample(type, 'failover', { kind: 'model_failover', to: 'm2' }),
        sample(type, 'generic', { kind: 'info' })
      ]
    case 'error':
      return [
        sample(type, 'basic', { message: '模型超时' }),
        sample(type, 'code', { error: 'RATE_LIMIT', message: '限流' })
      ]
    case 'file_ref':
    case 'compaction':
    case 'child_thread':
      return [
        sample(type, 'basic', { ref: type + '-1' }),
        sample(type, 'edge', { note: 'unknown-renderer-ok', payload: { nested: true } })
      ]
    default:
      // F9：未知新类型不报错，生成 UnknownItem 样例
      return [
        sample(type, 'basic', { note: `fixture for ${type}` }),
        sample(type, 'edge', { data: [1, 2, 3], change_set: { op: 'noop' } })
      ]
  }
}

const errors = []
for (const type of examples) {
  const file = join(fixturesDir, `${type}.json`)
  const defaults = defaultSamples(type)
  if (!existsSync(file)) {
    if (checkOnly) {
      errors.push(`缺少 fixture：${type}.json`)
      continue
    }
    writeFileSync(file, JSON.stringify(defaults, null, 2) + '\n')
    console.log(`wrote ${type}.json`)
    continue
  }
  let current
  try {
    current = JSON.parse(readFileSync(file, 'utf8'))
  } catch (e) {
    errors.push(`${type}.json 无法解析: ${e.message}`)
    continue
  }
  if (!Array.isArray(current) || current.length < 2) {
    errors.push(`${type}.json 须至少 2 个样例，当前 ${Array.isArray(current) ? current.length : 0}`)
    if (!checkOnly && Array.isArray(current) && current.length < 2) {
      writeFileSync(file, JSON.stringify(defaults, null, 2) + '\n')
      console.log(`repaired ${type}.json`)
    }
  }
}

// 未知类型样例：确保至少有一份 unknown 边界（不在 examples 中也可）
const unknownFile = join(fixturesDir, 'unknown.json')
if (!existsSync(unknownFile)) {
  const samples = [
    sample('totally_unknown', 'basic', { x: 1 }),
    sample('data', 'future-type', { rows: [] })
  ]
  if (checkOnly) {
    errors.push('缺少 fixture：unknown.json（未知类型兜底）')
  } else {
    writeFileSync(unknownFile, JSON.stringify(samples, null, 2) + '\n')
    console.log('wrote unknown.json')
  }
}

// 列出多余文件不失败；仅报告
if (!checkOnly) {
  const files = readdirSync(fixturesDir).filter((f) => f.endsWith('.json'))
  console.log(`fixtures: ${files.length} files; schema examples: ${examples.length}`)
}

if (errors.length) {
  for (const e of errors) console.error(e)
  process.exit(1)
}

if (checkOnly) {
  console.log(`OK: ${examples.length} itemType examples covered under ${fixturesDir}`)
}
