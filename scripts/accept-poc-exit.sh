#!/usr/bin/env bash
# 商业 PoC Gate / W1 Exit 验收（33 R2）
# 分两级：工程门禁（本脚本前半）+ 产品签字（签字页 EXIT 已签）
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 17 2>/dev/null || true)}"
echo "== Tioka PoC EXIT 验收 =="

fail=0
check_file() {
  local label="$1" path="$2"
  if [[ -f "$path" ]]; then
    echo "OK  $label"
  else
    echo "MISS $label -> $path"
    fail=1
  fi
}

echo "-- 工程门禁（R2 fidelity）--"
check_file "PartRenderer" "tioka-ui/src/parts/PartRenderer.vue"
check_file "normalizePart" "tioka-ui/src/parts/normalizePart.ts"
check_file "stream 单测" "tioka-modules/tioka-module-model/tioka-module-model-biz/src/test/java/com/tioka/model/application/OpenAiCompatibleClientStreamTest.java"
check_file "live LLM 冒烟脚本" "scripts/smoke-llm-live.sh"

rg -q 'streamComplete' tioka-modules/tioka-module-model/tioka-module-model-api/src/main/java/com/tioka/model/api/LlmClientApi.java \
  || { echo "MISS LlmClientApi.streamComplete"; fail=1; }
rg -q 'stream:true|"stream"\s*,\s*true|put\("stream", true\)' \
  tioka-modules/tioka-module-model/tioka-module-model-biz/src/main/java/com/tioka/model/application/OpenAiCompatibleClient.java \
  || { echo "MISS OpenAI stream:true"; fail=1; }
rg -q 'substring\(i,' tioka-modules/tioka-module-runtime/tioka-module-runtime-biz/src/main/java/com/tioka/runtime/application/RuntimeSessionService.java \
  && { echo "FAIL Runtime 仍含 substring 假切块"; fail=1; } \
  || echo "OK  Runtime 无 substring 假切块"
rg -q 'tioka-dev-sec-16' tioka-modules/tioka-module-system/tioka-module-system-biz/src/main/java/com/tioka/system/infrastructure/spi/LocalSecretStore.java \
  && rg -q 'master-key|TIOKA_SECRET_MASTER_KEY|require-external-key' \
       tioka-modules/tioka-module-system/tioka-module-system-biz/src/main/java/com/tioka/system/infrastructure/spi/LocalSecretStore.java \
  && echo "OK  SecretStore 支持外部 master key（可保留 local 回退）" \
  || { echo "MISS SecretStore 外部 master key"; fail=1; }
rg -q 'element-plus' tioka-ui/package.json && rg -q 'pinia' tioka-ui/package.json \
  && echo "OK  UI Pinia+Element Plus" \
  || { echo "MISS UI 栈升级"; fail=1; }
rg -q 'doImport|manifest|/api/skills' tioka-ui/src/views/Skills.vue \
  && echo "OK  Skills 非空壳（导入/列表）" \
  || { echo "MISS Skills 页仍空壳"; fail=1; }

if [[ "$fail" -ne 0 ]]; then
  echo "POC ENGINEERING FAIL"
  exit 1
fi
echo "POC ENGINEERING READY"

SIGN_FILE="${ROOT}/docs/architecture/implementation/w1-poc-gate-signoff.md"
if [[ ! -f "$SIGN_FILE" ]]; then
  echo "MISS signoff file: $SIGN_FILE"
  exit 1
fi
# 仅当状态行显式为「EXIT …：已签」才通过（避免命中「改为已签」说明文案）
if grep -E '^\s*-\s*\*\*EXIT（商业 PoC Gate）：已签\*\*' "$SIGN_FILE" >/dev/null \
   || grep -E 'EXIT（商业 PoC Gate）：已签[[:space:]]*$' "$SIGN_FILE" >/dev/null; then
  echo "product signoff: SIGNED — POC EXIT PASS"
  exit 0
fi

echo "product signoff: PENDING — see $SIGN_FILE"
echo "R2 engineering ready; product/sales must change EXIT to signed after walkthrough"
echo "POC EXIT PENDING"
exit 2
