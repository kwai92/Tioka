#!/usr/bin/env bash
# ENG-01：禁止依赖列表第一条的脆弱断言（除非同行或上一行有 // ordered:）
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
fail=0
while IFS= read -r -d '' file; do
  # 逐行扫描 data[0] / $.data[0]
  line_no=0
  prev=""
  while IFS= read -r line || [[ -n "$line" ]]; do
    line_no=$((line_no + 1))
    if [[ "$line" =~ data\[0\]|\$\.data\[0\] ]]; then
      if [[ "$line" =~ //\ *ordered: ]] || [[ "$prev" =~ //\ *ordered: ]]; then
        :
      else
        echo "FAIL $file:$line_no: 禁止 data[0] 断言（加 // ordered: 或改为按 id 匹配）"
        echo "  $line"
        fail=1
      fi
    fi
    prev="$line"
  done < "$file"
done < <(find "$root" -path '*/src/test/*' \( -name '*.java' -o -name '*.ts' -o -name '*.js' \) -print0)

if [[ "$fail" -ne 0 ]]; then
  exit 1
fi
echo "OK: no unordered data[0] assertions"
