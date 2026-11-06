import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'
import { gateSend, itemToPart, shouldAppendStreamPart, sidebarIsProductCost } from './chatUx'

const root = join(dirname(fileURLToPath(import.meta.url)), '..')
const chatVue = readFileSync(join(root, 'views/Chat.vue'), 'utf8')

describe('CH-01 / HCI-02 Chat UX gates', () => {
  it('rejects empty message and busy send', () => {
    expect(gateSend({ text: '  ', activeId: 'c1' })).toBe('消息不能为空')
    expect(gateSend({ text: 'hi', activeId: '' })).toBe('请先创建或选择会话')
    expect(gateSend({ text: 'hi', activeId: 'c1', sending: true })).toContain('等待')
    expect(gateSend({ text: 'hi', activeId: 'c1', sending: false })).toBeNull()
  })

  it('shouldAppendStreamPart still gates cancelled (compat)', () => {
    expect(shouldAppendStreamPart(false)).toBe(true)
    expect(shouldAppendStreamPart(true)).toBe(false)
  })

  it('Chat.vue wires multi-conversation switch + PartRenderer + streamThread', () => {
    expect(chatVue).toMatch(/v-for="c in conversations"/)
    expect(chatVue).toMatch(/@click="open\(c\.id\)"/)
    expect(chatVue).toMatch(/newConv/)
    expect(chatVue).toMatch(/<PartRenderer/)
    expect(chatVue).toMatch(/streamThread/)
    expect(chatVue).toMatch(/startChatTurn/)
    expect(chatVue).toMatch(/stopActiveTurn|function stop/)
    expect(chatVue).not.toMatch(/openSessionStream/)
    expect(chatVue).not.toMatch(/<pre[\s>]/)
  })

  it('Chat.vue shows reconnect banner, optimistic send, stop', () => {
    expect(chatVue).toMatch(/errorBanner/)
    expect(chatVue).toMatch(/gateSend/)
    expect(chatVue).toMatch(/重连中/)
    expect(chatVue).toMatch(/optimisticUser/)
    expect(chatVue).toMatch(/clientMsgId/)
    expect(chatVue).toMatch(/function stop/)
  })

  it('sidebar is cost/model not env dump', () => {
    expect(chatVue).toMatch(/成本 \/ 模型/)
    expect(chatVue).not.toMatch(/JSON\.stringify\(.*env/i)
    expect(chatVue).not.toMatch(/<pre>[\s\S]*env/)
    expect(sidebarIsProductCost('成本 / 模型')).toBe(true)
  })

  it('itemToPart maps pending user text', () => {
    expect(itemToPart({ itemType: 'message', role: 'user', content: {}, pending: true }).text).toBe(
      '发送中…'
    )
    expect(
      itemToPart({ itemType: 'message', role: 'user', content: { text: 'hi' }, pending: false }).text
    ).toBe('hi')
  })
})
