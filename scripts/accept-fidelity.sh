#!/usr/bin/env bash
# 保真验收（静态）：检查代码路径与配置面存在；不强制出网/不强制 Docker。
# 级别：fidelity-static（≠ 33 定义的 fidelity/exit；真保真见 R2+ / accept-poc-exit.sh）
# 与 accept.sh 分离：CI 默认可只跑 accept.sh；nightly / 本地可跑本脚本。
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
echo "== Tioka 保真验收（静态路径 / fidelity-static） =="
echo "说明: 通过仅表示路径存在；不等于非 mock 冒烟或 Exit"

fail=0
check() {
  local label="$1" path="$2"
  if [[ -f "$path" ]]; then
    echo "OK  $label"
  else
    echo "MISS $label -> $path"
    fail=1
  fi
}

check "MCP tools/call 实现" "tioka-modules/tioka-module-mcp/tioka-module-mcp-biz/src/main/java/com/tioka/mcp/application/McpAdminService.java"
grep -q 'tools/call' tioka-modules/tioka-module-mcp/tioka-module-mcp-biz/src/main/java/com/tioka/mcp/application/McpAdminService.java \
  || { echo "MISS MCP tools/call 字符串"; fail=1; }

check "飞书 OpenAPI Adapter" "tioka-modules/tioka-module-connector/tioka-module-connector-biz/src/main/java/com/tioka/connector/infrastructure/FeishuImChannelAdapter.java"
grep -q 'tenant_access_token' tioka-modules/tioka-module-connector/tioka-module-connector-biz/src/main/java/com/tioka/connector/infrastructure/FeishuImChannelAdapter.java \
  || { echo "MISS feishu tenant_access_token"; fail=1; }

check "OIDC Login" "tioka-modules/tioka-module-system/tioka-module-system-biz/src/main/java/com/tioka/system/application/oidc/OidcLoginService.java"
grep -q 'oidc/{idp}/callback' tioka-modules/tioka-module-system/tioka-module-system-biz/src/main/java/com/tioka/system/api/AuthController.java \
  || { echo "MISS oidc callback API"; fail=1; }

check "ext profile" "tioka-server/src/main/resources/application-ext.yml"
check "pg schema" "tioka-server/src/main/resources/schema-pg.sql"
check "compose pgvector" "deploy/compose.yml"
grep -q 'pgvector/pgvector' deploy/compose.yml || { echo "MISS pgvector image"; fail=1; }

check "Knowledge pgvector 写入" "tioka-modules/tioka-module-knowledge/tioka-module-knowledge-biz/src/main/java/com/tioka/knowledge/application/KnowledgeService.java"
grep -q 'embedding_vec' tioka-modules/tioka-module-knowledge/tioka-module-knowledge-biz/src/main/java/com/tioka/knowledge/application/KnowledgeService.java \
  || { echo "MISS embedding_vec write path"; fail=1; }

grep -q 'raw_content' tioka-modules/tioka-module-connector/tioka-module-connector-biz/src/main/java/com/tioka/connector/application/FeishuDocSyncService.java \
  || { echo "MISS feishu doc raw_content"; fail=1; }
echo "OK  飞书 Doc Sync OpenAPI"

grep -q '@vue-flow/core' tioka-ui/package.json || { echo "MISS @vue-flow/core"; fail=1; }
echo "OK  Vue Flow 依赖"

check "fidelity profile" "tioka-server/src/main/resources/application-fidelity.yml"
grep -q 'mock: false' tioka-server/src/main/resources/application-fidelity.yml \
  || { echo "MISS fidelity mock:false"; fail=1; }

if [[ "${TIOKA_FIDELITY_REQUIRED:-0}" == "1" ]]; then
  echo "[required] running smoke-llm-live with fail-closed"
  if ! TIOKA_FIDELITY_REQUIRED=1 "$ROOT/scripts/smoke-llm-live.sh"; then
    echo "MISS live LLM smoke under TIOKA_FIDELITY_REQUIRED"
    fail=1
  fi
elif [[ "${TIOKA_FIDELITY_LIVE:-0}" == "1" ]]; then
  echo "[live] compose + spring profile=ext 冒烟需人工/环境变量，见 application-ext.yml"
fi

if [[ "$fail" -ne 0 ]]; then
  echo "FIDELITY FAIL"
  exit 1
fi
echo "FIDELITY PASS (static paths${TIOKA_FIDELITY_REQUIRED:+ + required live})"
