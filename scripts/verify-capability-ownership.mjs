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

// 7. 接入平面（36 号 §20）：平台资产归位与第二接入方验收
const ic = list.integrationContract;
check(ic !== undefined, '清单登记了平台接入契约');
if (ic !== undefined) {
  for (const [label, key] of [['平台 schema', 'platformSchema'],
    ['应用实例', 'applicationInstance'], ['接入护栏', 'platformGuard'],
    ['自检器', 'selfCheck'], ['自检报告', 'selfCheckReport'],
    ['失效钩子', 'invalidationHook'], ['骨架 POM', 'secondAppExample'],
    ['骨架验收', 'onboardingTest']]) {
    check(typeof ic[key] === 'string' && exists(ic[key]), `清单登记并存在${label}：${key}`);
  }
  for (const s of ic.guardScripts) {
    check(exists(s), `平台契约守门脚本存在：${s}`);
  }
  const platformSchema = JSON.parse(read(ic.platformSchema));
  const appInstance = JSON.parse(read(ic.applicationInstance));
  check(platformSchema.$id === ic.platformId, '平台 schema 标识与清单一致');
  check(appInstance.platformContract === ic.platformId, '应用实例声明遵守平台契约');
  check(platformSchema.additionalProperties === false, '平台 schema 禁止应用自造字段');
  check(platformSchema.properties[ic.fieldNaming] !== undefined, '平台 schema 使用应用无关字段名');
  check(platformSchema.properties[ic.bannedLegacyField] === undefined,
    '平台 schema 不含绑定第一个应用的旧字段名');
  check(Array.isArray(appInstance[ic.fieldNaming]) && appInstance[ic.bannedLegacyField] === undefined,
    '应用实例按平台字段名登记权限码');
  const examplePom = read(ic.secondAppExample);
  check(examplePom.includes('<groupId>com.tioka</groupId>') === false,
    '第二接入方骨架不依赖第一个业务应用构件');
  check(read(ic.onboardingTest).includes('com.tioka.framework.security.PermissionEvaluator'),
    '骨架验收显式断言不存在 Tioka 框架类');
  check(read('tioka-foundation/foundation-starter-client/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports')
    .includes('FoundationIntegrationAutoConfiguration'), '接入护栏已注册为平台自动装配');
  check(ic.selfCheckItems === 6, '六项自检数量冻结');
}

// P1 运行平面：缓存与失效归平台，Tioka 适配层不得自行实现或回落本地权限
const cachePath = 'tioka-foundation/foundation-starter-client/src/main/java/com/foundation/starter/client/authz/';
check(exists(cachePath + 'AuthorizationSnapshotCache.java')
  && exists(cachePath + 'CachingAuthorizationSnapshotClient.java')
  && exists(cachePath + 'AuthorizationSnapshotInvalidationListener.java'),
  '平台侧版本化缓存、装饰器与事件失效监听器齐备');
const adapterPath = 'tioka-server/src/main/java/com/tioka/server/foundation/FoundationAuthorizationSnapshotSource.java';
const adapter = read(adapterPath);
check(adapter.includes('client.permissionsFor(personId)'), 'Tioka 适配层仍只做端口转发');
check(!adapter.includes('AuthorizationSnapshotCache')
  && !adapter.includes('CachingAuthorizationSnapshotClient')
  && !adapter.includes('PrincipalHolder'),
  'Tioka 适配层不自建缓存、不回落本地权限集');
check(read('tioka-foundation/foundation-starter-client/src/main/java/com/foundation/starter/client/config/FoundationClientProperties.java')
  .includes('foundation.client.authz.*'),
  '轻量路径缓存配置收敛在 client starter 命名空间，不与 security starter 串线');

// 降级策略单一真相源：应用不得自行把授权能力加进降级白名单（ADP-08 / 待决策 D4）
check(!appYml.includes('foundation.client.degrade.capabilities')
  && !appYml.includes('stale-cache'),
  '应用未自行放宽降级白名单，授权降级策略仍由平台唯一出口决定');
check(read('tioka-foundation/foundation-starter-client/src/main/java/com/foundation/starter/client/config/FoundationClientProperties.java')
  .includes('List.of("dict", "param", "org", "person")'),
  '平台降级白名单不含 authz / security，授权类恒 FAIL_CLOSED');

// 回归信号可判定：不得让测试类在字段初始化处构造依赖 JNI 原生库的求值器
const cedarTest = read('tioka-modules/tioka-module-governance/tioka-module-governance-biz/src/test/java/com/tioka/governance/application/policy/CedarEvaluatorTest.java');
check(!/static final PolicyEvaluator/.test(cedarTest),
  'Cedar 测试不在字段初始化处构造求值器，避免类初始化失败污染整个模块');
check(exists('tioka-modules/tioka-module-governance/tioka-module-governance-biz/src/test/java/com/tioka/governance/application/policy/CedarNativeSupport.java')
  && cedarTest.includes('assumeTrue(CedarNativeSupport.available()'),
  '缺 cedar-java 原生库时按 Assumptions 跳过并计入 skipped，而非报 error');
check(!cedarTest.includes('@Disabled'),
  '未用 @Disabled 长期屏蔽用例，装好原生库后自动恢复执行');

// 分层命名契约：*Test = 离线单测（surefire），*IT = 需容器（surefire 排除、failsafe 执行）。
// 依赖容器的类若命名为 *Test，会被 surefire 抓进 mvn test，在无 Docker 的机器上必然失败，
// 并把整个模块判红、掩盖同模块其它真实失败（RuntimeApiContractTest 曾如此多轮）。
const containerMarkers = ['IntegrationTestSupport', 'TestContainers.register', '@IntegrationTest'];
// 注意：Windows 上 walk() 返回的路径分隔符是 '\'，必须先归一化再比较，
// 否则 includes('/src/test/') 恒为 false，本守门会退化成空集合恒真通过。
const offenders = walk('tioka-server/src/test').concat(walk('tioka-modules'))
  .map((p) => rel(p))
  .filter((p) => p.endsWith('.java') && p.includes('/src/test/'))
  // 排除测试基建（IntegrationTestSupport / TestContainers / GateSeed 等辅助类自身含这些标记）
  .filter((p) => !p.includes('/support/'))
  .filter((p) => !/(IT|E2E)\.java$/.test(path.basename(p)))
  .filter((p) => {
    const src = read(p);
    // 必须真的是测试类（有 @Test），而非注解或工具类
    return src.includes('@Test') && containerMarkers.some((m) => src.includes(m));
  });
check(offenders.length === 0,
  '依赖容器的测试类均命名为 *IT.java，不被 surefire 抓进离线单测阶段'
  + (offenders.length ? '：' + offenders.join(', ') : ''));

// Spring 组件若声明多个 public 构造器，必须恰好一个标 @Autowired，否则 Spring 回退找无参
// 构造器并抛 "No default constructor found"，整条依赖链装配失败、所有 @SpringBootTest 起不来。
// 该缺陷离线单测无法暴露（单测直接 new），只在 CI backend job 爆发。
//
// 两处必须注意，否则守门会假通过（两者都曾实际踩到）：
//  1) @Autowired 有「简写 import」与「全限定 @org.springframework...Autowired」两种写法；
//  2) 必须先剥离 Java 注释——javadoc 里提到 @Autowired 会让裸 includes('Autowired') 误判为已标注。
const SPRING_STEREOTYPES = ['@Component', '@Service', '@Repository', '@Configuration',
  '@RestController', '@Controller'];
const stripJavaComments = (src) => src
  .replace(/\/\*[\s\S]*?\*\//g, '')
  .replace(/\/\/[^\n]*/g, '');
const multiCtorNoAutowire = walk('tioka-modules').concat(walk('tioka-server'), walk('tioka-framework'))
  .map((p) => rel(p))
  .filter((p) => p.endsWith('.java') && p.includes('/src/main/'))
  .filter((p) => {
    const src = stripJavaComments(read(p));
    if (!SPRING_STEREOTYPES.some((a) => new RegExp('^' + a + '\\b', 'm').test(src))) return false;
    const cls = path.basename(p, '.java');
    const ctors = src.match(new RegExp('public\\s+' + cls + '\\s*\\(', 'g')) || [];
    if (ctors.length < 2) return false;
    return !src.includes('Autowired');
  });
check(multiCtorNoAutowire.length === 0,
  'Spring 组件多构造器时必须标注 @Autowired，否则 CI 上装配失败'
  + (multiCtorNoAutowire.length ? '：' + multiCtorNoAutowire.join(', ') : ''));


console.log(`capability ownership: ${ok.length} passed, ${fail.length} failed`);
if (fail.length > 0) {
  for (const f of fail) console.error('  FAIL ' + f);
  process.exit(1);
}
console.log('OK: 能力归属清单与代码、文档、版本坐标一致');
