#!/usr/bin/env bash
# SLICE 验收：mock/H2 垂直切片 + 任务勾选存在 + 门禁用例方法存在。
# 不等于商业 PoC Gate / G1–G7 Exit。Exit 见 accept-poc-exit.sh（R2+）。
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 17)}"
export PATH="$JAVA_HOME/bin:$PATH"
cd "$ROOT"
echo "== Tioka SLICE 自动验收 =="
echo "说明: 本脚本只证明 slice；fidelity→accept-fidelity.sh；exit→accept-poc-exit.sh（见 docs/.../33）"
echo "[1/3] Maven test"
mvn -q test
echo "[2/3] UI build"
(cd tioka-ui && npm run build --silent)
echo "[3/3] 任务勾选 + G1–G7 门禁套件审计（slice）"
python3 - <<'PY'
from pathlib import Path
import re
plan = Path("docs/architecture/implementation/31-W0至W5实施计划.md").read_text(encoding="utf-8")
assert "完成度图例" in plan, "31 缺少完成度图例（R0 复位）"
assert plan.count("**Exit 状态**") >= 5, "31 各波 Exit 状态行不足（R0 复位）"
assert "exit `[ ]`" in plan, "31 应声明 exit 未达"
boxes = re.findall(r"- \[([ x])\] \*\*(W[0-5]-\d+)\*\*", plan)
done = [b for b in boxes if b[0] == "x"]
todo = [b for b in boxes if b[0] == " "]
print(f"切片任务勾选: {len(done)} done / {len(boxes)} total; remaining={len(todo)}")
assert not todo, f"仍有未完成切片任务: {[t[1] for t in todo[:10]]}"
gate = Path("tioka-server/src/test/java/com/tioka/server/GateRegressionSuiteTest.java").read_text(encoding="utf-8")
for name in [
    "g1_runtimeSteerCancelAndTools",
    "g1b_freezeDifficultyFailoverCost",
    "g2_dualCardsImAndFork",
    "g3_knowledgeHybridCitationEvalAndSources",
    "g4_orchAttentionWhyLater",
    "g5_flowNodeCatalogBranchAndPublication",
    "g6_catalogQuotaAuditEnv",
    "g7_enterpriseAdaptersAirgapScim",
]:
    assert f"void {name}(" in gate, f"缺少门禁用例: {name}"
for cls in [
    "tioka-server/src/test/java/com/tioka/server/GoldenPathE2ETest.java",
    "tioka-server/src/test/java/com/tioka/server/OrchAttentionTest.java",
    "tioka-server/src/test/java/com/tioka/server/GateRegressionSuiteTest.java",
    "tioka-server/src/test/java/com/tioka/server/HealthTest.java",
]:
    assert Path(cls).exists(), cls
auth33 = Path("docs/architecture/implementation/33-差距收口与后续推进计划.md")
assert auth33.exists(), "缺少 33 推进计划"
assert "执行权威" in auth33.read_text(encoding="utf-8")
print("SLICE ACCEPT: checklist + gate methods present; automated suites green")
print("NOT exit: 勿将本结果表述为 G1–G7 / 商业 PoC Gate 已过")
PY
echo "SLICE ACCEPT PASS"
