# 文档单源（ENG-07）

## 规则

1. **唯一来源**：`docs/architecture/**`（**X3 裁决**：`feishu-docs/Tioka` 为手工镜像，**不参与代码↔设计一致性校验基线**）
2. **飞书镜像**：`node scripts/publish-feishu-docs.mjs`（输出 `feishu-docs/Tioka/_generated/`，页首「自动生成，勿改」）
3. **完成度表**：`node scripts/gen-progress.mjs` → `docs/architecture/implementation/progress.md`（禁止手工编辑）
4. **CI**：main 合入后由 `docs` job 运行上述脚本；未配置 `FEISHU_APP_ID` / `FEISHU_APP_SECRET` 则跳过远程推送（仅本地镜像）。*（docs job 写入 `.github/workflows/ci.yml` 需越界申请，本包未改 workflow。）*
5. **归档**：与 architecture 重复的手工镜像迁入 `docs/archive/`

> **X4 说明（2026-11-09 整改）**：企业架构 **40–46 序列**在镜像与权威源各存一份（共 7 份），其中 **5 份行数不同**——`40-BA`（221 vs 181）、`43-AA`（176 vs 151）、`44-EA`（219 vs 196）、`45-FE`（252 vs 202）、`46-架构多轮评审记录`（146 vs 139）；`41-TA`、`42-DA` 两侧一致。镜像另**缺** `47-GL-统一名词释义`、`48-CS-编码规约摘要` 两份（镜像中的 `47-图形多轮评审记录` 是另一份文档）。按 X3 裁决，镜像仅作发布面，**不参与校验基线**；一切以 `docs/architecture/**` 为唯一权威源。

## 本地命令

```bash
node scripts/gen-progress.mjs
node scripts/publish-feishu-docs.mjs
bash scripts/check-connector-coverage.sh   # CON-04 覆盖率 ≥ 60%
```
