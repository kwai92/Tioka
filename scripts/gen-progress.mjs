#!/usr/bin/env node
/**
 * ENG-07：由 CI / 本地 Surefire 与 @Tag("gate") 结果生成完成度表。
 * 输出：docs/architecture/implementation/progress.md（禁止手工编辑）。
 *
 * 用法：node scripts/gen-progress.mjs
 * 扫描仓库内 surefire-reports 目录下的 XML。
 */
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(__dirname, "..");
const OUT = path.join(ROOT, "docs", "architecture", "implementation", "progress.md");
const PLAN = path.join(
  ROOT,
  "docs",
  "architecture",
  "implementation",
  "34-M0至M4推进规划与工作项.md"
);

function walkXml(dir, acc = []) {
  if (!fs.existsSync(dir)) return acc;
  for (const ent of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, ent.name);
    if (ent.isDirectory()) {
      if (ent.name === "node_modules" || ent.name === ".git") continue;
      walkXml(full, acc);
    } else if (ent.name.endsWith(".xml") && full.includes("surefire-reports")) {
      acc.push(full);
    }
  }
  return acc;
}

function parseSurefire(xml) {
  const tests = Number((xml.match(/\stests="(\d+)"/) || [])[1] || 0);
  const failures = Number((xml.match(/\sfailures="(\d+)"/) || [])[1] || 0);
  const errors = Number((xml.match(/\serrors="(\d+)"/) || [])[1] || 0);
  const skipped = Number((xml.match(/\sskipped="(\d+)"/) || [])[1] || 0);
  const name = ((xml.match(/<testsuite[^>]*\sname="([^"]+)"/) || [])[1] || "").trim();
  const gate = /GateIT|@Tag\("gate"\)|gate\./i.test(xml) || /GateIT/.test(name);
  return { tests, failures, errors, skipped, name, gate };
}

function extractWorkItems(planMd) {
  const items = [];
  const re = /^\|\s*([A-Z]{2,5}-\d+[a-z]?)\s*\|\s*([^|]+)\|/gm;
  let m;
  while ((m = re.exec(planMd))) {
    const id = m[1].trim();
    if (id === "ID") continue;
    items.push({ id, title: m[2].trim().replace(/\*\*/g, "") });
  }
  return items;
}

function main() {
  const plan = fs.existsSync(PLAN) ? fs.readFileSync(PLAN, "utf8") : "";
  const workItems = extractWorkItems(plan);
  const xmlFiles = walkXml(ROOT);
  let total = 0,
    failures = 0,
    errors = 0,
    skipped = 0,
    gatePass = 0,
    gateFail = 0;
  const suites = [];
  for (const f of xmlFiles) {
    const xml = fs.readFileSync(f, "utf8");
    const s = parseSurefire(xml);
    if (!s.tests && !s.name) continue;
    suites.push(s);
    total += s.tests;
    failures += s.failures;
    errors += s.errors;
    skipped += s.skipped;
    if (s.gate) {
      if (s.failures + s.errors > 0) gateFail++;
      else gatePass++;
    }
  }

  const generatedAt = new Date().toISOString();
  const lines = [];
  lines.push("<!-- 自动生成，勿改。由 scripts/gen-progress.mjs 写入。CODEOWNERS：CI 机器人 + 架构负责人。 -->");
  lines.push("");
  lines.push("# 实施完成度（自动生成）");
  lines.push("");
  lines.push(`> 生成时间：${generatedAt}`);
  lines.push(">");
  lines.push("> 数据来源：仓库内 Surefire 报告（`**/surefire-reports/*.xml`）与 34 号计划工作项表。");
  lines.push("> 禁止手工编辑本文件；本地可运行 `node scripts/gen-progress.mjs` 刷新。");
  lines.push("");
  lines.push("## 测试汇总");
  lines.push("");
  lines.push("| 指标 | 值 |");
  lines.push("| --- | --- |");
  lines.push(`| Surefire 报告文件 | ${xmlFiles.length} |`);
  lines.push(`| 用例总数 | ${total} |`);
  lines.push(`| 失败 | ${failures} |`);
  lines.push(`| 错误 | ${errors} |`);
  lines.push(`| 跳过 | ${skipped} |`);
  lines.push(`| Gate 套件通过 | ${gatePass} |`);
  lines.push(`| Gate 套件失败 | ${gateFail} |`);
  lines.push("");
  lines.push("## 34 号计划工作项（登记）");
  lines.push("");
  lines.push(`共解析 **${workItems.length}** 个工作项 ID（完成状态以闸门/CI 为准，本表仅作索引）。`);
  lines.push("");
  lines.push("| ID | 标题 |");
  lines.push("| --- | --- |");
  for (const w of workItems.slice(0, 200)) {
    lines.push(`| ${w.id} | ${w.title} |`);
  }
  if (workItems.length > 200) {
    lines.push(`| … | 另有 ${workItems.length - 200} 项省略 |`);
  }
  lines.push("");
  lines.push("## Gate 相关套件");
  lines.push("");
  const gates = suites.filter((s) => s.gate);
  if (gates.length === 0) {
    lines.push("_本次未发现名称含 GateIT 的 Surefire 套件（可能尚未执行 -Pgate）。_");
  } else {
    lines.push("| 套件 | tests | failures | errors | skipped |");
    lines.push("| --- | ---: | ---: | ---: | ---: |");
    for (const g of gates) {
      lines.push(`| ${g.name} | ${g.tests} | ${g.failures} | ${g.errors} | ${g.skipped} |`);
    }
  }
  lines.push("");

  fs.mkdirSync(path.dirname(OUT), { recursive: true });
  fs.writeFileSync(OUT, lines.join("\n"), "utf8");
  console.log(`wrote ${path.relative(ROOT, OUT)} (workItems=${workItems.length}, xml=${xmlFiles.length})`);
}

main();
