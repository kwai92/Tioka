#!/usr/bin/env node
// Foundation 中台化：能力归属清单与重复概念的机器守门。
// 只读校验，不修改任何文件。运行：node scripts/verify-capability-ownership.mjs
// 校验内容：冻结的重复实现路径、Workspace 渗透度、双键同源、单轨版本坐标、文档与清单同步。

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const ok = [];
const fail = [];
const check = (cond, msg) => (cond ? ok : fail).push(msg);
const read = (rel) => fs.readFileSync(path.join(ROOT, rel), 'utf8');
const exists = (rel) => fs.existsSync(path.join(ROOT, rel));
const rel = (abs) => path.relative(ROOT, abs).replace(/\\/g, '/');
const same = (a, b) => JSON.stringify([...a].sort()) === JSON.stringify([...b].sort());

function walk(dir, out = []) {
  if (!fs.existsSync(dir)) return out;
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    if (['target', '.git', 'node_modules', '.workbuddy', 'dist'].includes(entry.name)) continue;
    const p = path.join(dir, entry.name);
    if (entry.isDirectory()) walk(p, out);
    else if (entry.isFile()) out.push(p);
  }
  return out;
}

const list = JSON.parse(read('docs/architecture/capability-ownership-v1.json'));
check(list.version === 1, '清单版本为 v1');
check(Array.isArray(list.frozenDuplicates) && list.frozenDuplicates.length >= 8,
  '冻结重复类清单非空');
check(list.frozenDuplicates.every((d) => ['adapter', 'migrate', 'keep-in-tioka', 'foundation-internal'].includes(d.resolution)),
  '所有冻结项都声明了处置方式');
check(list.foundationOwned.every((c) => ['dual', 'planned'].includes(c.status)),
  '中台能力归属状态合法');

const TIOKA_DIRS = ['tioka-framework', 'tioka-modules', 'tioka-server'];
const javaFiles = TIOKA_DIRS.flatMap((d) => walk(path.join(ROOT, d))).filter((f) => f.endsWith('.java'));
const foundationRoot = path.join(ROOT, 'tioka-foundation');
const foundationJava = fs.existsSync(foundationRoot)
  ? walk(foundationRoot).filter((f) => f.endsWith('.java'))
  : null;
if (foundationJava === null) {
  console.log('WARN: 未检出 tioka-foundation/，跳过 Foundation 侧路径比对');
}

// 1. 冻结的重复实现：实际扫描结果必须与清单逐字一致，防止重复概念静默扩散
for (const d of list.frozenDuplicates) {
  const name = d.className + '.java';
  const actualTioka = javaFiles.filter((f) => path.basename(f) === name).map(rel);
  check(same(actualTioka, d.tiokaPaths),
    `冻结类 ${d.className} 的 Tioka 路径一致（实际 ${actualTioka.length} / 登记 ${d.tiokaPaths.length}）`);
  if (foundationJava !== null) {
    const actualFdn = foundationJava.filter((f) => path.basename(f) === name).map(rel);
    check(same(actualFdn, d.foundationPaths),
      `冻结类 ${d.className} 的 Foundation 路径一致（实际 ${actualFdn.length} / 登记 ${d.foundationPaths.length}）`);
  }
}

// 2. Workspace 渗透度冻结：数字变化必须重新评估，而不是悄悄漂移
let wsFiles = 0;
let wsHits = 0;
let wsLines = 0;
for (const f of javaFiles) {
  const text = fs.readFileSync(f, 'utf8');
  const m = text.match(/workspace/gi);
  if (m) {
    wsFiles += 1;
    wsHits += m.length;
  }
  wsLines += text.split(/\r?\n/).filter((line) => /workspace/i.test(line)).length;
}
const sqlFiles = fs.readdirSync(path.join(ROOT, 'docs/contracts/db')).filter((f) => f.endsWith('.sql'));
const wsSql = sqlFiles.filter((f) => /workspace_id/i.test(read(`docs/contracts/db/${f}`))).length;
check(wsFiles === list.workspace.javaFiles, `Workspace Java 文件数 ${wsFiles} 与清单一致`);
check(wsLines === list.workspace.javaMatchedLines, `Workspace 匹配行数 ${wsLines} 与清单一致`);
check(wsHits === list.workspace.javaOccurrences, `Workspace 出现次数 ${wsHits} 与清单一致`);
check(wsSql === list.workspace.migrationsWithWorkspaceId, `含 workspace_id 的迁移 ${wsSql} 与清单一致`);
check(sqlFiles.length === list.workspace.migrationsTotal, `迁移总数 ${sqlFiles.length} 与清单一致`);
check(list.workspace.propagation === 'forbidden-for-derived-apps', '派生应用禁止引入 Workspace 已声明');

// 3. 配置契约与单轨版本坐标
const yml = read(list.configContract.file);
const hits = yml.split(list.configContract.sharedSource).length - 1;
check(hits >= list.configContract.keys.length,
  `双模式键由同一环境变量派生（命中 ${hits} 处）`);
check(exists(list.configContract.guardTest), '双键一致性契约测试存在');
const rootPom = read('pom.xml');
const depPom = read('tioka-dependencies/pom.xml');
check(rootPom.includes(`<version>${list.singleTrackVersion}</version>`), '根 pom parent 锁定单轨版本');
check(depPom.includes(`<foundation.version>${list.singleTrackVersion}</foundation.version>`), 'BOM 版本锁定单轨版本');
check(rootPom.includes('-adapt') === false && depPom.includes('-adapt') === false,
  '主仓库 pom 不含已废弃的分轨坐标');

// 4. 归属清单与文档、真实文件三者同步
for (const c of list.foundationOwned) {
  for (const p of c.tiokaDuplicates) {
    check(exists(p), `中台能力「${c.capability}」的 Tioka 重复实现路径存在：${p}`);
  }
}
const doc = read('docs/architecture/capability-ownership.md');
for (const c of [...list.foundationOwned, ...list.platformOwned, ...list.adapterRequired]) {
  check(doc.includes(c.capability), `文档覆盖能力「${c.capability}」`);
}
for (const d of list.frozenDuplicates) {
  check(doc.includes(d.className), `文档覆盖冻结类 ${d.className}`);
}

// 5. 权限判定双实现与映射契约（36 号文档 §18）
const perm = list.permissionContract;
check(perm !== undefined, '清单登记了权限码映射契约');
if (perm !== undefined) {
  check(exists(perm.file), '权限码映射契约文件存在');
  check(exists(perm.guardScript), '权限码映射守门脚本存在');
  check(exists(perm.evaluatorPort), '权限判定端口存在');
  check(exists(perm.snapshotSource), '中台授权快照端口存在');
  check(perm.wildcardPolicy === 'deny-elevation', '通配不提升策略已冻结');
  const permJson = exists(perm.file) ? JSON.parse(read(perm.file)) : null;
  check(permJson !== null && permJson.version === 1
    && permJson.wildcardPolicy === 'deny-elevation',
    '映射契约版本与通配策略与清单一致');
}
for (const name of ['身份上下文适配', '权限码映射']) {
  const item = list.adapterRequired.find((c) => c.capability === name);
  check(item !== undefined && item.status === 'done', `适配项「${name}」已完成`);
}

// 6. 授权快照接入（36 号 §19）：清单、代码、装配接线三者同步
for (const [label, key] of [['底座快照客户端', 'snapshotClient'],
  ['应用适配器', 'snapshotAdapter'], ['装配入口', 'adapterConfiguration']]) {
  check(typeof perm[key] === 'string' && exists(perm[key]), `清单登记并存在${label}：${key}`);
}
const snapshotAdapterItem = list.adapterRequired.find((c) => c.capability === '授权快照接入');
check(snapshotAdapterItem !== undefined && snapshotAdapterItem.status === 'done',
  '适配项「授权快照接入」已完成');
const clientConfiguration = read('tioka-foundation/foundation-starter-client/src/main/java/com/foundation/starter/client/config/FoundationClientAutoConfiguration.java');
check(clientConfiguration.includes('BearerTokenSupplier serviceAccountTokenProvider'),
  '底座出站客户端已接上服务账号令牌');
const serverPom = read('tioka-server/pom.xml');
check(serverPom.includes('<artifactId>foundation-starter-client</artifactId>')
  && !serverPom.includes('<artifactId>foundation-starter-security</artifactId>'),
  '应用层只引入底座只读客户端，不引入服务端安全 starter');
const appYml = read(list.configContract.file);
check(appYml.includes('FOUNDATION_CLIENT_ID') && appYml.includes('FOUNDATION_TOKEN_URI'),
  '接入模式的服务账号凭据有环境变量入口');

console.log(`capability ownership: ${ok.length} passed, ${fail.length} failed`);
if (fail.length > 0) {
  for (const f of fail) console.error('  FAIL ' + f);
  process.exit(1);
}
console.log('OK: 能力归属清单与代码、文档、版本坐标一致');
