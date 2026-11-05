#!/usr/bin/env bash
# 非 mock 冒烟清单。
# TIOKA_FIDELITY_REQUIRED=1 且未设 TIOKA_SMOKE_LIVE=1 → FAIL（禁止 SKIP 假绿）
set -euo pipefail
echo "== mock=false 冒烟清单 =="
echo "1) export TIOKA_MODEL_MOCK=false TIOKA_MCP_MOCK=false 或 profile=fidelity"
echo "2) 配置 SecretStore / Provider baseUrl（OpenAI 兼容）"
echo "3) SPRING_PROFILES_ACTIVE=local,fidelity 启动后走黄金路径"

if [[ "${TIOKA_SMOKE_LIVE:-0}" != "1" ]]; then
  if [[ "${TIOKA_FIDELITY_REQUIRED:-0}" == "1" ]]; then
    echo "FAIL: fidelity required but TIOKA_SMOKE_LIVE!=1 (refusing SKIP=0 fake pass)"
    exit 1
  fi
  echo "SMOKE SKIP (static)"
  exit 0
fi
echo "SMOKE LIVE 未实现全自动；请人工验证或使用 smoke-llm-live.sh"
exit 0
