#!/usr/bin/env bash
# R2-02：非 mock LLM 冒烟。
# - 默认（slice CI）：无密钥 → SKIP exit 0
# - TIOKA_FIDELITY_REQUIRED=1：无密钥 → FAIL exit 1（禁止假绿）
set -euo pipefail

if [[ -z "${TIOKA_LLM_BASE_URL:-}" || -z "${TIOKA_LLM_API_KEY:-}" ]]; then
  if [[ "${TIOKA_FIDELITY_REQUIRED:-0}" == "1" ]]; then
    echo "FAIL: fidelity required but TIOKA_LLM_BASE_URL / TIOKA_LLM_API_KEY not set"
    exit 1
  fi
  echo "SKIP: TIOKA_LLM_BASE_URL / TIOKA_LLM_API_KEY not set"
  exit 0
fi

BASE="${TIOKA_LLM_BASE_URL%/}"
MODEL="${TIOKA_LLM_MODEL:-gpt-4o-mini}"
URL="${BASE}/chat/completions"

echo "== smoke-llm-live: POST ${URL} (stream=false) =="
RESP="$(curl -sS -w "\n%{http_code}" "${URL}" \
  -H "Authorization: Bearer ${TIOKA_LLM_API_KEY}" \
  -H "Content-Type: application/json" \
  -d "{\"model\":\"${MODEL}\",\"messages\":[{\"role\":\"user\",\"content\":\"ping from tioka smoke\"}],\"stream\":false}")"

HTTP_CODE="$(echo "${RESP}" | tail -n1)"
BODY="$(echo "${RESP}" | sed '$d')"
echo "HTTP ${HTTP_CODE}"
echo "${BODY}" | head -c 500
echo

if [[ "${HTTP_CODE}" -lt 200 || "${HTTP_CODE}" -ge 300 ]]; then
  echo "FAIL: non-2xx from OpenAI-compatible endpoint"
  exit 1
fi
if ! echo "${BODY}" | grep -q '"choices"'; then
  echo "FAIL: response missing choices"
  exit 1
fi
echo "OK: live LLM smoke passed"
exit 0
