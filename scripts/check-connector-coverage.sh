#!/usr/bin/env bash
# CON-04：connector-biz 行覆盖率 ≥ 60%（M1-04）。
# 根 pom surefire 硬编码 argLine，无法用 -DargLine 注入 javaagent；
# 本脚本用 JaCoCo 离线插桩 + surefire，避免越界改 pom（pom jacoco check 见 PR 越界申请）。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
MODULE="tioka-modules/tioka-module-connector/tioka-module-connector-biz"
THRESHOLD="${CONNECTOR_COVERAGE_MIN:-60}"
JACOCO_VER="${JACOCO_VER:-0.8.15}"
CLI_DIR="${TMPDIR:-/tmp}/jacoco-cli-con04"
CLI="$CLI_DIR/org.jacoco.cli-${JACOCO_VER}-nodeps.jar"
AGENT_RT="$HOME/.m2/repository/org/jacoco/org.jacoco.agent/${JACOCO_VER}/org.jacoco.agent-${JACOCO_VER}-runtime.jar"
TESTS="FeishuImChannelAdapterTest,WeComImChannelAdapterTest,DingTalkImChannelAdapterTest,FeishuDocSyncIT,ImOutboxIT,ExampleBizSystemAdapterTest"

restore_classes() {
  if [[ -d "$MODULE/target/classes.bak" ]]; then
    rm -rf "$MODULE/target/classes"
    mv "$MODULE/target/classes.bak" "$MODULE/target/classes"
  fi
}
trap restore_classes EXIT

mvn -q org.apache.maven.plugins:maven-dependency-plugin:3.7.0:copy \
  -Dartifact="org.jacoco:org.jacoco.cli:${JACOCO_VER}:jar:nodeps" \
  -DoutputDirectory="$CLI_DIR"
if [[ ! -f "$AGENT_RT" ]]; then
  mvn -q org.apache.maven.plugins:maven-dependency-plugin:3.7.0:get \
    -Dartifact="org.jacoco:org.jacoco.agent:${JACOCO_VER}:jar:runtime"
fi

mvn -q -pl "$MODULE" -am test-compile
restore_classes
rm -rf "$MODULE/target/jacoco-classes" "$MODULE/target/classes.bak"
java -jar "$CLI" instrument "$MODULE/target/classes" --dest "$MODULE/target/jacoco-classes"
cp -a "$MODULE/target/classes" "$MODULE/target/classes.bak"
rm -rf "$MODULE/target/classes"
mv "$MODULE/target/jacoco-classes" "$MODULE/target/classes"

RT_UNPACK="${TMPDIR:-/tmp}/jacoco-rt-con04"
rm -rf "$RT_UNPACK" && mkdir -p "$RT_UNPACK"
( cd "$RT_UNPACK" && jar xf "$AGENT_RT" )
cp -R "$RT_UNPACK/org" "$MODULE/target/classes/"

EXEC="$ROOT/$MODULE/target/jacoco.exec"
rm -f "$EXEC"
mvn -pl "$MODULE" -q surefire:test \
  -Dtest="$TESTS" \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Djacoco-agent.destfile="$EXEC"

restore_classes
trap - EXIT

OUT="$MODULE/target/site/jacoco"
rm -rf "$OUT" && mkdir -p "$OUT"
java -jar "$CLI" report "$EXEC" \
  --classfiles "$MODULE/target/classes" \
  --sourcefiles "$MODULE/src/main/java" \
  --xml "$OUT/jacoco.xml" \
  --html "$OUT" \
  --csv "$OUT/jacoco.csv"

python3 - "$OUT/jacoco.csv" "$THRESHOLD" <<'PY'
import csv, sys
path, thr = sys.argv[1], float(sys.argv[2])
missed = covered = 0
with open(path, newline="") as f:
    for row in csv.DictReader(f):
        missed += int(row["LINE_MISSED"])
        covered += int(row["LINE_COVERED"])
total = missed + covered
pct = 0.0 if total == 0 else 100.0 * covered / total
print(f"connector-biz LINE coverage: {pct:.2f}% ({covered}/{total})")
print(f"report: {path}")
if pct + 1e-9 < thr:
    print(f"FAIL: below threshold {thr}%", file=sys.stderr)
    sys.exit(1)
print(f"PASS: >= {thr}%")
PY
