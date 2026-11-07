import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import {
  applyRuntimeEvent,
  clearDiscardNotice,
  createThreadState,
  insertOptimisticUser,
  maxSeq,
  seedFromItems,
  setReconnecting,
  sortedItems,
  type ItemView,
  type RuntimeEvent,
  type ThreadState
} from '@/chat/threadState'

const cache = new Map<string, ReturnType<typeof buildThreadStore>>()

function buildThreadStore(threadId: string) {
  return defineStore(`thread:${threadId}`, () => {
    const state = ref<ThreadState>(createThreadState())

    const items = computed(() => sortedItems(state.value))
    const activeTurn = computed(() => state.value.activeTurn)
    const lastEventId = computed(() => state.value.lastEventId || (maxSeq(state.value) ? String(maxSeq(state.value)) : ''))
    const discardNotice = computed(() => state.value.discardNotice)
    const reconnecting = computed(() => state.value.reconnecting)
    const approvals = computed(() => {
      const map: Record<string, { approvalId: string; status: string; decidedBy?: string; toolName?: string }> =
        {}
      for (const [k, v] of state.value.approvals.entries()) {
        map[k] = v
      }
      return map
    })
    const turnBusy = computed(() => {
      const s = state.value.activeTurn?.status
      return !!s && !['COMPLETED', 'FAILED', 'CANCELLED'].includes(s)
    })

    function reset() {
      state.value = createThreadState()
    }

    function seed(views: ItemView[]) {
      state.value = seedFromItems(state.value, views)
    }

    function onEvent(ev: RuntimeEvent, eventId?: string) {
      state.value = applyRuntimeEvent(state.value, ev, eventId)
    }

    function optimisticUser(clientMsgId: string, text: string) {
      state.value = insertOptimisticUser(state.value, clientMsgId, text)
    }

    function markReconnecting(v: boolean) {
      state.value = setReconnecting(state.value, v)
    }

    function ackDiscardNotice() {
      state.value = clearDiscardNotice(state.value)
    }

    function setActiveTurn(turnId: string, status = 'QUEUED') {
      state.value = {
        ...state.value,
        activeTurn: { turnId, status }
      }
    }

    return {
      threadId,
      state,
      items,
      activeTurn,
      lastEventId,
      discardNotice,
      reconnecting,
      approvals,
      turnBusy,
      reset,
      seed,
      onEvent,
      optimisticUser,
      markReconnecting,
      ackDiscardNotice,
      setActiveTurn
    }
  })
}

/** 每个 thread 一个 Pinia store 实例（M0-03 / HCI-02）。 */
export function useThreadStore(threadId: string) {
  let store = cache.get(threadId)
  if (!store) {
    store = buildThreadStore(threadId)
    cache.set(threadId, store)
  }
  return store()
}

/** 单测用：清空动态 store 缓存。 */
export function __resetThreadStoreCache() {
  cache.clear()
}
