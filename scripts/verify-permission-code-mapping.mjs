#!/usr/bin/env node
// Foundation 中台化：权限码映射契约与双实现的机器守门。
// 只读校验，不修改任何文件。运行：node scripts/verify-permission-code-mapping.mjs
// 校验内容：契约与 Permissions.ALL_CONCRETE 同步、策略不得放宽、双实现不回落本地权限。

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const ok = [];
const fail = [];
const check = (cond, msg) => (cond ? ok : fail).push(msg);
const read = (rel) => fs.readFileSync(path.join(ROOT, rel), 'utf8');
const exists = (rel) => fs.existsSync(path.join(ROOT, rel));
const same = (a, b) => JSON.stringify([...a].sort()) === JSON.stringify([...b].sort());

const CONTRACT = 'tioka-framework/src/main/resources/contracts/permission-code-mapping-v1.json';
const SEC = 'tioka-framework/src/main/java/com/tioka/framework/security/';
const paths = {
  permissions: SEC + 'Permissions.java',
  mapper: SEC + 'PermissionCodeMapper.java',
  evaluatorPort: SEC + 'PermissionEvaluator.java',
  local: SEC + 'LocalPermissionEvaluator.java',
  foundation: SEC + 'FoundationPermissionEvaluator.java',
  snapshotSource: SEC + 'AuthorizationSnapshotSource.java',
  aspect: 'tioka-modules/tioka-module-system/tioka-module-system-biz/src/main/java/com/tioka/system/application/authz/PermissionAspect.java',
  configuration: 'tioka-framework/src/main/java/com/tioka/framework/foundation/FoundationModeConfiguration.java',
};

check(exists(CONTRACT), '权限码映射契约存在');
for (const [name, p] of Object.entries(paths)) {
  check(exists(p), `${name} 源文件存在：${p}`);
}
if (fail.length > 0) {
  console.log(`permission-code-mapping: ${ok.length} passed, ${fail.length} failed`);
  for (const f of fail) console.error('  FAIL ' + f);
  process.exit(1);
}

const contract = JSON.parse(read(CONTRACT));
const permissionsJava = read(paths.permissions);
const mapperJava = read(paths.mapper);
const foundationJava = read(paths.foundation);
const aspectJava = read(paths.aspect);
const configJava = read(paths.configuration);

// 1. 契约策略不得放宽
check(contract.version === 1, '契约版本为 v1');
check(contract.wildcardPolicy === 'deny-elevation', '通配策略为 deny-elevation（禁止直通提升超管）');
check(contract.unknownCodePolicy === 'ignore', '未知码策略为 ignore（最小权限）');
check(contract.requiredCodePolicy === 'deny-unregistered', '未登记声明码策略为 deny-unregistered');
check(Array.isArray(contract.superAdminCodes), '超管码集合为数组');
check(contract.superAdminCodes.every((c) => typeof c === 'string' && c.trim() !== '' && !c.includes('*')),
  '超管码不含空白或通配项');
check(contract.aliases !== null && typeof contract.aliases === 'object' && !Array.isArray(contract.aliases),
  '别名为对象');

// 2. 契约目录与 Permissions.ALL_CONCRETE 逐项一致
const consts = new Map();
for (const m of permissionsJava.matchAll(/public static final String (\w+)\s*=\s*"([^"]+)";/g)) {
  consts.set(m[1], m[2]);
}
const block = permissionsJava.match(/ALL_CONCRETE\s*=\s*\{([\s\S]*?)\};/);
check(block !== null, 'Permissions.ALL_CONCRETE 数组可解析');
const declaredNames = block === null ? [] : [...block[1].matchAll(/\b([A-Z][A-Z0-9_]*)\b/g)].map((m) => m[1]);
const declaredCodes = declaredNames.map((n) => consts.get(n));
check(declaredCodes.every((c) => typeof c === 'string'), 'ALL_CONCRETE 中的常量都能解析出权限码字面量');
const contractCodes = contract.tiokaPermissionCodes;
check(Array.isArray(contractCodes) && contractCodes.length > 0, '契约权限码目录非空');
check(new Set(contractCodes).size === contractCodes.length, '契约权限码无重复项');
check(contractCodes.every((c) => typeof c === 'string' && c.trim() !== '' && !c.includes('*')),
  '契约权限码均为具体项，不含通配或空白');
check(same(declaredCodes, contractCodes),
  `契约目录与 ALL_CONCRETE 集合一致（${contractCodes.length} 项 / 声明 ${declaredCodes.length} 项）`);
check(JSON.stringify(declaredCodes) === JSON.stringify(contractCodes),
  '契约目录与 ALL_CONCRETE 顺序一致（新增权限点必须同步两处）');
for (const [from, to] of Object.entries(contract.aliases)) {
  check(!from.includes('*') && contractCodes.includes(to) && !to.includes('*'),
    `别名 ${from} -> ${to} 指向已登记的具体权限码`);
}

// 3. 实现不得漂移：通配必须被丢弃、接入模式不得回落本地权限
check(mapperJava.includes('isWildcard(code)') && mapperJava.includes('grantsSuperAdmin'),
  'Mapper 显式执行通配丢弃与超管单独判定');
check(mapperJava.includes('contracts/permission-code-mapping-v1.json'),
  'Mapper 从 classpath 契约加载，不硬编码权限码');
check(!mapperJava.includes('new ObjectMapper(') && mapperJava.includes('loadDefault(ObjectMapper objectMapper)'),
  'Mapper 遵守 ENG-05，不自行构造 ObjectMapper');
check(configJava.includes('ObjectProvider<ObjectMapper>') && configJava.includes('loadDefault(objectMapper)'),
  '装配入口把 Spring ObjectMapper 注入映射器');
check(/firstMissing\(/.test(foundationJava) && /grantsSuperAdmin\(raw\)/.test(foundationJava),
  '接入模式判定先映射再比较');
check(/source\.permissionsFor\(/.test(foundationJava), '接入模式从授权快照端口取权限');
check(!/hasPermission\(|\.permissions\(\)/.test(foundationJava),
  '接入模式不得读取应用本地权限集（防止绕过中台授权）');
check(/return required\[0\]/.test(foundationJava), '数据源不可用时 fail-closed 返回缺失码');
check(/isRegistered\(code\)/.test(foundationJava), '未登记的声明码被拒绝');

// 4. 切面与装配接线
check(aspectJava.includes('PermissionEvaluator') && aspectJava.includes('permissions.firstMissing('),
  '权限切面委托给判定端口');
check(!aspectJava.includes('hasPermission('), '权限切面不再直接按本地权限集判定');
check(configJava.includes('ConditionalOnFoundationMode(FoundationMode.LOCAL)')
  && configJava.includes('ConditionalOnFoundationMode(FoundationMode.FOUNDATION)'),
  '两种模式互斥装配判定器');
check(configJava.includes('AuthorizationSnapshotSource') && configJava.includes('no AuthorizationSnapshotSource bean'),
  '接入模式缺少授权数据源时启动期拒绝');

console.log(`permission-code-mapping: ${ok.length} passed, ${fail.length} failed`);
if (fail.length > 0) {
  for (const f of fail) console.error('  FAIL ' + f);
  process.exit(1);
}
console.log('OK: 权限码映射契约与双实现逻辑一致');
