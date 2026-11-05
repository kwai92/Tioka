import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'
import { gateSend, shouldAppendStreamPart, sidebarIsProductCost } from './chatUx'

const root = join(dirname(fileURLToPath(import.meta.url)), '..')
const chatVue = readFileSync(join(root, 'views/Chat.vue'), 'utf8')

describe('CH-01 Chat UX gates', () => {
  it('rejects empty message and cancelled send', () => {
    expect(gateSend({ text: '  ', activeId: 'c1', cancelled: false })).toBe('消息不能为空')
    expect(gateSend({ text: 'hi', activeId: '', cancelled: false })).toBe('请先创建或选择会话')
    expect(gateSend({ text: 'hi', activeId: 'c1', cancelled: true })).toContain('已取消')
    expect(gateSend({ text: 'hi', activeId: 'c1', cancelled: false })).toBeNull()
  })

  it('stops stream append after cancel', () => {
    expect(shouldAppendStreamPart(false)).toBe(true)
    expect(shouldAppendStreamPart(true)).toBe(false)
  })

  it('Chat.vue wires multi-conversation switch + PartRenderer', () => {
    expect(chatVue).toMatch(/v-for="c in conversations"/)
    expect(chatVue).toMatch(/@click="open\(c\.id\)"/)
    expect(chatVue).toMatch(/newConv/)
    expect(chatVue).toMatch(/<PartRenderer/)
    expect(chatVue).not.toMatch(/<pre[\s>]/)
  })

  it('Chat.vue shows error banner and cancel stops streaming', () => {
    expect(chatVue).toMatch(/errorBanner/)
    expect(chatVue).toMatch(/gateSend/)
    expect(chatVue).toMatch(/shouldAppendStreamPart\(cancelled/)
    expect(chatVue).toMatch(/cancelled\.value = true/)
  })

  it('sidebar is cost/model not env dump', () => {
    expect(chatVue).toMatch(/成本 \/ 模型/)
    expect(chatVue).not.toMatch(/JSON\.stringify\(.*env/i)
    expect(chatVue).not.toMatch(/<pre>[\s\S]*env/)
    expect(sidebarIsProductCost('成本 / 模型')).toBe(true)
  })
})
