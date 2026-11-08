# 文档单源（ENG-07）

## 规则

1. **唯一来源**：`docs/architecture/**`
2. **飞书镜像**：`node scripts/publish-feishu-docs.mjs`（输出 `feishu-docs/Tioka/_generated/`，页首「自动生成，勿改」）
3. **完成度表**：`node scripts/gen-progress.mjs` → `docs/architecture/implementation/progress.md`（禁止手工编辑）
4. **CI**：main 合入后由 `docs` job 运行上述脚本；未配置 `FEISHU_APP_ID` / `FEISHU_APP_SECRET` 则跳过远程推送（仅本地镜像）。*（docs job 写入 `.github/workflows/ci.yml` 需越界申请，本包未改 workflow。）*
5. **归档**：与 architecture 重复的手工镜像迁入 `docs/archive/`

## 本地命令

```bash
node scripts/gen-progress.mjs
node scripts/publish-feishu-docs.mjs
bash scripts/check-connector-coverage.sh   # CON-04 覆盖率 ≥ 60%
```
