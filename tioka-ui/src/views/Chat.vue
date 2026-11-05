<template>
  <div class="chat">
    <header>
      <h1>会话</h1>
      <div class="row">
        <el-select v-model="agentVersionId" placeholder="选择已发布 Agent" style="min-width: 220px">
          <el-option
            v-for="a in agents"
            :key="a.publishedVersionId"
            :label="a.name"
            :value="a.publishedVersionId"
          />
        </el-select>
        <el-button type="primary" :disabled="!agentVersionId" @click="newConv">新会话</el-button>
      </div>
    </header>
    <div class="layout">
      <ul class="list">
        <li
          v-for="c in conversations"
          :key="c.id"
          :class="{ active: c.id === activeId }"
          @click="open(c.id)"
        >
          {{ c.title }}
        </li>
      </ul>
      <section class="panel">
        <div v-if="errorBanner" class="error-banner">{{ errorBanner }}</div>
        <div class="msgs" ref="box">
          <div
            v-for="(m, idx) in displayMessages"
            :key="m.id || idx"
            class="bubble"
            :class="m.role"
          >
            <PartRenderer
              :part="m.part"
              :conversation-id="activeId"
              :message-id="m.messageId"
              @submitted="onCardSubmitted"
            />
          </div>
          <div v-if="displayStreamingParts.length" class="bubble assistant live">
            <div class="stream-label">streaming</div>
            <PartRenderer
              v-for="(p, i) in displayStreamingParts"
              :key="i"
              :part="p"
              :conversation-id="activeId"
              @submitted="onCardSubmitted"
            />
          </div>
        </div>
        <aside v-if="cost.length" class="cost">
          <h3>成本 / 模型</h3>
          <div v-for="(e, i) in cost" :key="i">
            {{ e.modelRef }} · {{ e.routeReason || e.reason }} · {{ e.promptTokens + e.completionTokens }} tok
          </div>
        </aside>
        <form @submit.prevent="send">
          <el-input v-model="draft" placeholder="输入消息，含 echo 可触发工具…" />
          <el-button :disabled="!runtimeSessionId" @click="cancel">取消</el-button>
          <el-button type="primary" native-type="submit" :disabled="!activeId || !draft || sending">
            发送
          </el-button>
        </form>
      </section>
    </div>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { api, openSessionStream } from '../api'
import { gateSend, shouldAppendStreamPart } from '../chat/chatUx'
import PartRenderer from '../parts/PartRenderer.vue'
import { appendStreamPart, expandPartsForDisplay } from '../parts/normalizePart'

const agents = ref([])
const conversations = ref([])
const messages = ref([])
const agentVersionId = ref('')
const activeId = ref('')
const runtimeSessionId = ref('')
const draft = ref('')
const sending = ref(false)
const streamingParts = ref([])
const cost = ref([])
const errorBanner = ref('')
const cancelled = ref(false)
const box = ref(null)
let es = null

const displayMessages = computed(() => {
  const out = []
  for (const m of messages.value) {
    if (Array.isArray(m.parts) && m.parts.length) {
      const filtered = m.parts
        .filter((p) => !(p.type === 'text' && p.payload && p.payload.delta))
        .map((p) => ({
          type: p.type || 'text',
          text: p.text || (p.type === 'text' ? m.text : '') || '',
          payload: p.payload || {},
          id: p.id
        }))
      const expanded = expandPartsForDisplay(filtered)
      for (const [i, part] of expanded.entries()) {
        out.push({
          id: `${m.id}-${part.id || part.type}-${i}`,
          messageId: m.id,
          role: m.role,
          part
        })
      }
    } else {
      out.push({
        id: m.id,
        messageId: m.id,
        role: m.role,
        part: { type: 'text', text: m.text || '' }
      })
    }
  }
  return out
})

const displayStreamingParts = computed(() => expandPartsForDisplay(streamingParts.value))

async function refresh() {
  agents.value = (await api('/api/agents')).filter((a) => a.publishedVersionId)
  conversations.value = await api('/api/chat/conversations')
}

async function newConv() {
  errorBanner.value = ''
  cancelled.value = false
  if (!agentVersionId.value) {
    errorBanner.value = '请先选择已发布 Agent'
    return
  }
  try {
    const c = await api('/api/chat/conversations', {
      method: 'POST',
      body: JSON.stringify({
        title: '会话 ' + new Date().toLocaleTimeString(),
        agentVersionId: agentVersionId.value
      })
    })
    await refresh()
    await open(c.id)
  } catch (e) {
    errorBanner.value = e?.message || '创建会话失败'
  }
}

function closeStream() {
  if (es) {
    es.close()
    es = null
  }
}

function scrollBottom() {
  nextTick(() => {
    if (box.value) box.value.scrollTop = box.value.scrollHeight
  })
}

async function open(id) {
  closeStream()
  streamingParts.value = []
  errorBanner.value = ''
  cancelled.value = false
  activeId.value = id
  const conv = conversations.value.find((c) => c.id === id)
  runtimeSessionId.value = conv?.runtimeSessionId || ''
  messages.value = await api(`/api/chat/conversations/${id}/messages`)
  if (runtimeSessionId.value) {
    es = openSessionStream(runtimeSessionId.value, (part) => {
      if (!shouldAppendStreamPart(cancelled.value)) return
      streamingParts.value = appendStreamPart(streamingParts.value, part)
      scrollBottom()
    })
    try {
      cost.value = await api(`/api/runtime/cost/sessions/${runtimeSessionId.value}`)
    } catch {
      cost.value = []
    }
  }
}

async function send() {
  errorBanner.value = ''
  const blocked = gateSend({
    text: draft.value,
    activeId: activeId.value,
    cancelled: cancelled.value
  })
  if (blocked) {
    errorBanner.value = blocked
    return
  }
  const text = draft.value.trim()
  draft.value = ''
  sending.value = true
  streamingParts.value = []
  try {
    await api(`/api/chat/conversations/${activeId.value}/messages`, {
      method: 'POST',
      body: JSON.stringify({ text, clientMsgId: crypto.randomUUID() })
    })
    messages.value = await api(`/api/chat/conversations/${activeId.value}/messages`)
    streamingParts.value = []
    if (runtimeSessionId.value) {
      cost.value = await api(`/api/runtime/cost/sessions/${runtimeSessionId.value}`)
    }
  } catch (e) {
    errorBanner.value = e?.message || '发送失败'
  } finally {
    sending.value = false
  }
}

async function cancel() {
  if (!runtimeSessionId.value) return
  try {
    await api(`/api/runtime/sessions/${runtimeSessionId.value}/cancel`, { method: 'POST', body: '{}' })
    cancelled.value = true
    streamingParts.value = []
    errorBanner.value = '已取消；后续增量已停止展示'
  } catch (e) {
    errorBanner.value = e?.message || '取消失败'
  }
}

async function onCardSubmitted() {
  if (!activeId.value) return
  messages.value = await api(`/api/chat/conversations/${activeId.value}/messages`)
  scrollBottom()
}

onMounted(refresh)
onBeforeUnmount(closeStream)
</script>

<style scoped>
.chat { display: grid; gap: 16px; height: calc(100vh - 48px); }
header { display: flex; justify-content: space-between; align-items: end; gap: 12px; }
h1 { margin: 0; font-size: 1.4rem; }
.row { display: flex; gap: 8px; align-items: center; }
.layout { display: grid; grid-template-columns: 220px 1fr; gap: 12px; min-height: 0; flex: 1; height: 100%; }
.list { list-style: none; margin: 0; padding: 0; border: 1px solid #243041; border-radius: 12px; overflow: auto; }
.list li { padding: 10px 12px; cursor: pointer; color: var(--muted); }
.list li.active, .list li:hover { background: #1c2735; color: var(--text); }
.panel { display: grid; grid-template-rows: auto 1fr auto auto; border: 1px solid #243041; border-radius: 12px; min-height: 0; }
.error-banner { margin: 8px 12px 0; padding: 8px 10px; border-radius: 8px; background: #3a1d1d; color: #ffb4b4; font-size: 13px; }
.msgs { padding: 16px; overflow: auto; display: grid; gap: 10px; align-content: start; }
.bubble { max-width: 85%; padding: 10px 12px; border-radius: 12px; display: grid; gap: 6px; }
.bubble.user { justify-self: end; background: #2a4a74; }
.bubble.assistant { justify-self: start; background: #1b2735; }
.bubble.live { border: 1px dashed #3d8bfd; }
.stream-label { font-size: 11px; color: var(--muted); text-transform: uppercase; }
.cost { padding: 8px 16px; border-top: 1px solid #243041; color: var(--muted); font-size: 12px; }
.cost h3 { margin: 0 0 4px; font-size: 12px; color: var(--text); }
form { display: grid; grid-template-columns: 1fr auto auto; gap: 8px; padding: 12px; border-top: 1px solid #243041; align-items: center; }
</style>
