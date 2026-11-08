#!/usr/bin/env node
/**
 * ENG-07：将 docs/architecture 同步为飞书镜像目录（只读）。
 * 页首标注「自动生成，勿改」。需 FEISHU_APP_ID/SECRET 时可由 CI docs job 再推送到飞书；
 * 未配置机密则仅生成本地镜像。
 *
 * 用法：node scripts/publish-feishu-docs.mjs [--out feishu-docs/Tioka]
 */
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(__dirname, "..");
const SRC = path.join(ROOT, "docs", "architecture");
const args = process.argv.slice(2);
const outIdx = args.indexOf("--out");
const OUT =
  outIdx >= 0 && args[outIdx + 1]
    ? path.resolve(ROOT, args[outIdx + 1])
    : path.join(ROOT, "feishu-docs", "Tioka", "_generated");

const BANNER = `> **自动生成，勿改。** 来源：\`docs/architecture\`。由 \`scripts/publish-feishu-docs.mjs\` 生成。\n\n`;

function walk(dir, base = dir, acc = []) {
  for (const ent of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, ent.name);
    if (ent.isDirectory()) walk(full, base, acc);
    else if (ent.isFile() && ent.name.endsWith(".md")) acc.push(path.relative(base, full));
  }
  return acc;
}

function ensureDir(p) {
  fs.mkdirSync(p, { recursive: true });
}

function main() {
  if (!fs.existsSync(SRC)) {
    console.error("missing source:", SRC);
    process.exit(1);
  }
  ensureDir(OUT);
  const files = walk(SRC);
  let n = 0;
  for (const rel of files) {
    const src = path.join(SRC, rel);
    const dest = path.join(OUT, rel);
    ensureDir(path.dirname(dest));
    const body = fs.readFileSync(src, "utf8");
    const stamped = body.startsWith("> **自动生成") ? body : BANNER + body;
    fs.writeFileSync(dest, stamped, "utf8");
    n++;
  }
  const meta = {
    generatedAt: new Date().toISOString(),
    source: "docs/architecture",
    fileCount: n,
    pushSkipped: !(process.env.FEISHU_APP_ID && process.env.FEISHU_APP_SECRET),
  };
  fs.writeFileSync(path.join(OUT, "_meta.json"), JSON.stringify(meta, null, 2) + "\n");
  console.log(`published ${n} markdown files → ${path.relative(ROOT, OUT)}`);
  if (meta.pushSkipped) {
    console.log("FEISHU_APP_ID/SECRET 未配置：跳过远程推送（本地镜像已生成）");
  } else {
    console.log("FEISHU 机密已配置：远程推送由 CI docs job 扩展（本脚本仅本地镜像）");
  }
}

main();
