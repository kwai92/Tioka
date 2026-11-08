/** HCI-03：API Key 明文仅创建时暂存；关闭弹窗必须清除。 */

export type ApiKeyPlaintextState = {
  keyId: string | null
  plaintext: string | null
}

export function emptyApiKeyPlaintext(): ApiKeyPlaintextState {
  return { keyId: null, plaintext: null }
}

export function setApiKeyPlaintext(
  state: ApiKeyPlaintextState,
  keyId: string,
  plaintext: string
): ApiKeyPlaintextState {
  return { keyId, plaintext }
}

/** 弹窗关闭后调用：store 中明文清空。 */
export function clearApiKeyPlaintext(_state?: ApiKeyPlaintextState): ApiKeyPlaintextState {
  return emptyApiKeyPlaintext()
}
