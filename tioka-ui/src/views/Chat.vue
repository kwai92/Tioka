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
        <div v-if="reconnectingBanner" class="reconnect-banner">重连中</div>
        <div v-if="discardBanner" class="info-banner">{{ discardBanner }}</div>
        <div v-if="errorBanner" class="error-banner">{{ errorBanner }}</div>
        <div class="msgs" ref="box">
          <div
            v-for="(m, idx) in displayMessages"
            :key="m.id || idx"
            class="bubble"
            :class="[m.role, { pending: m.pending }]"
          >
            <PartRenderer
              :part="m.part"
              :conversation-id="activeId"
              :message-id="m.messageId"
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
          <el-button :disabled="!threadId || !turnBusy" @click="stop">停止</el-button>
          <el-button type="primary" native-type="submit" :disabled="!activeId || !draft || sending || turnBusy">
            发送
          </el-button>
        </form>
      </section>
    </div>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { api } from '../api'
import { listConversationItems, startChatTurn, stopActiveTurn } from '../api/chatAsync'
import { streamThread } from '../api/stream'
import { gateSend, itemToPart } from '../chat/chatUx'
import { useThreadStore } from '../stores/thread'
import PartRenderer from '../parts/PartRenderer.vue'

const agents = ref([])
const conversations = ref([])
const agentVersionId = ref('')
const activeId = ref('')
const threadId = ref('')
const draft = ref('')
const sending = ref(false)
const cost = ref([])
const errorBanner = ref('')
const discardBanner = ref('')
const box = ref(null)

let abort = null

const threadStore = computed(() => (threadId.value ? useThreadStore(threadId.value) : null))

const reconnectingBanner = computed(() => !!threadStore.value?.reconnecting)
const turnBusy = computed(() => !!threadStore.value?.turnBusy)
const displayMessages = computed(() => {
  const store = threadStore.value
  if (!store) return []
  return store.items.map((it) => ({
    id: it.itemId,
    messageId: it.itemId,
    role: it.role || 'assistant',
    pending: !!it.pending,
    part: itemToPart(it)
  }))
})

watch(
  () => threadStore.value?.discardNotice,
  (v) => {
    if (v) {
      discardBanner.value = v
      threadStore.value?.ackDiscardNotice()
    }
  }
)

async function refresh() {
  agents.value = (await api('/api/agents')).filter((a) => a.publishedVersionId)
  conversations.value = await api('/api/chat/conversations')
}

async function newConv() {
  errorBanner.value = ''
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
  if (abort) {
    abort.abort()
    abort = null
  }
}

function scrollBottom() {
  nextTick(() => {
    if (box.value) box.value.scrollTop = box.value.scrollHeight
  })
}

async function open(id) {
  closeStream()
  errorBanner.value = ''
  discardBanner.value = ''
  activeId.value = id
  const conv = conversations.value.find((c) => c.id === id)
  // CHT-02：conversation.threadId（兼容 runtimeSessionId）
  threadId.value = conv?.threadId || conv?.runtimeSessionId || ''

  if (!threadId.value) {
    errorBanner.value = '会话未绑定 Runtime thread'
    return
  }

  const store = threadStore.value
  store?.reset()

  try {
    const items = await listConversationItems(id, 0)
    store.seed(items)
  } catch (e) {
    errorBanner.value = e?.message || '加载消息失败'
  }

  const after = store.lastEventId || ''
  abort = new AbortController()
  streamThread(
    threadId.value,
    {
      lastEventId: after,
      onEvent(ev, eventId) {
        threadStore.value?.onEvent(ev, eventId)
        scrollBottom()
      },
      onConnection(state) {
        threadStore.value?.markReconnecting(state === 'reconnecting')
      }
    },
    abort.signal
  ).catch(() => {
    /* abort / fatal */
  })

  try {
    cost.value = await api(`/api/runtime/cost/sessions/${threadId.value}`)
  } catch {
    cost.value = []
  }
  scrollBottom()
}

async function send() {
  errorBanner.value = ''
  const blocked = gateSend({
    text: draft.value,
    activeId: activeId.value,
    sending: sending.value || turnBusy.value
  })
  if (blocked) {
    errorBanner.value = blocked
    return
  }
  const store = threadStore.value
  if (!threadId.value || !store) {
    errorBanner.value = '会话未绑定 Runtime thread'
    return
  }
  const text = draft.value.trim()
  const clientMsgId = crypto.randomUUID()
  draft.value = ''
  sending.value = true
  store.optimisticUser(clientMsgId, text)
  scrollBottom()
  try {
    const accepted = await startChatTurn({
      conversationId: activeId.value,
      text,
      clientMsgId
    })
    if (accepted.threadId) {
      threadId.value = accepted.threadId
    }
    store.setActiveTurn(accepted.turnId, accepted.status || 'QUEUED')
    // 用户 item 可能只入库未推 SSE：拉一次列表替换乐观消息
    const items = await listConversationItems(activeId.value, 0)
    store.seed(items)
  } catch (e) {
    errorBanner.value = e?.message || '发送失败'
  } finally {
    sending.value = false
    scrollBottom()
  }
}

async function stop() {
  if (!activeId.value) return
  try {
    await stopActiveTurn(activeId.value)
    // 输入态在收到 turn.cancelled 后由 store 清空；此处不强制
  } catch (e) {
    errorBanner.value = e?.message || '停止失败'
  }
}

async function onCardSubmitted() {
  const store = threadStore.value
  if (!activeId.value || !store) return
  const items = await listConversationItems(activeId.value, 0)
  store.seed(items)
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
.panel { display: grid; grid-template-rows: auto auto auto 1fr auto auto; border: 1px solid #243041; border-radius: 12px; min-height: 0; }
.reconnect-banner { margin: 8px 12px 0; padding: 6px 10px; border-radius: 8px; background: #2a3340; color: #9ecbff; font-size: 13px; }
.info-banner { margin: 8px 12px 0; padding: 8px 10px; border-radius: 8px; background: #2a3a1d; color: #c8e6a0; font-size: 13px; }
.error-banner { margin: 8px 12px 0; padding: 8px 10px; border-radius: 8px; background: #3a1d1d; color: #ffb4b4; font-size: 13px; }
.msgs { padding: 16px; overflow: auto; display: grid; gap: 10px; align-content: start; }
.bubble { max-width: 85%; padding: 10px 12px; border-radius: 12px; display: grid; gap: 6px; }
.bubble.user { justify-self: end; background: #2a4a74; }
.bubble.assistant { justify-self: start; background: #1b2735; }
.bubble.pending { opacity: 0.75; }
.cost { padding: 8px 16px; border-top: 1px solid #243041; color: var(--muted); font-size: 12px; }
.cost h3 { margin: 0 0 4px; font-size: 12px; color: var(--text); }
form { display: grid; grid-template-columns: 1fr auto auto; gap: 8px; padding: 12px; border-top: 1px solid #243041; align-items: center; }
</style>
