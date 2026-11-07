# S13 — Skill 资产管理详细设计

> 模块：`tioka-module-skill`  
> 形态：以 `SKILL.md`（及附属脚本/资源）为包；对标 LibreChat Skills / Claude Skills / dsh skills  
> 运行：由 S1 在组装提示词与工具权限时加载；本域负责**管理、版本、分发、授权**

---

## 1. 定位与边界

Skill = **可安装的能力说明书 + 可选可执行附属物**（脚本、模板、参考文件），让 Agent 在特定任务上更稳。

| 边界内 | 边界外 |
| --- | --- |
| 包格式、导入、版本、签名、启停、授权、市场 | 解释器沙箱实现（S1） |
| 与 Agent 的绑定模式（manual/auto/always） | MCP 工具协议（S12） |

---

## 2. 完整能力地图

| 能力簇 | 能力项 | 波次 |
| --- | --- | --- |
| **包格式** | SKILL.md frontmatter（name/description/权限声明）；附属目录 scripts/refs | W2 |
| **导入** | ZIP 上传、Git URL、本地路径、Nacos/对象存储（企业） | W2 |
| **校验** | Schema、危险脚本扫描、权限声明完整性 | W2 |
| **版本** | semver；不可变制品；回滚 | W2 |
| **签名** | 租户签名/官方签名；仅允许已签名（可配） | W3 |
| **启用模式** | `manual` 用户点选；`auto` 模型按描述选用；`always` 注入 | W2 |
| **权限声明** | 所需工具、网络、文件系统范围；与 Agent 沙箱求交 | W2 |
| **绑定** | Agent / 空间默认 Skill 集 | W2 |
| **冲突** | 同名 Skill 优先级、禁用 | W2 |
| **市场** | 官方/租户目录、上下架审批 | W4 |
| **热更新** | 新版本发布后 Agent 跟随或钉版本 | W2–W3 |
| **观测** | 使用次数、关联失败 | W3 |
| **RBAC** | skill:install/publish/bind | W2 |

---

## 3. 领域模型

| 实体 | 说明 |
| --- | --- |
| **SkillPackage** | 逻辑 Skill |
| **SkillVersion** | 制品：内容哈希、存储位置、manifest |
| **SkillManifest** | 解析后的 name、description、mode 默认、permissions |
| **SkillRef** | Agent Spec 内引用（非独立表）；M1 `skillVersionId` ≡ package.id（[47-GL §1.3](../enterprise/47-GL-统一名词释义.md)） |
| **SkillSignature** | 签名与证书引用 |
| **SkillListing** | 市场上架信息 |

---

## 4. 组件结构

```
skill-biz
├── package/    解析 SKILL.md、打包校验
├── store/      制品存储（OSS/本地）
├── version/    发布回滚
├── bind/       Agent 绑定
├── sign/       签名校验
├── market/     上架
└── scan/       安全扫描钩子
```

---

## 5. 生命周期

```
UPLOADED → SCANNING → READY → PUBLISHED
PUBLISHED → DEPRECATED
Agent 绑定 → 运行时按 mode 注入 system 段 / 工具提示
```

---

## 6. 管理 API

| API | 说明 |
| --- | --- |
| SkillPackageApi | list/get |
| SkillImportApi | importZip/importGit |
| SkillVersionApi | publish、rollback |
| SkillBindApi | bindAgent、unbind |
| SkillMarketApi | list/publishListing |
| SkillScanApi | 扫描报告 |

---

## 7. MQ

`tioka-skill`：`skill.published` `skill.bound` `skill.revoked`

---

## 8. 数据模型

`sk_package` `sk_version` `sk_manifest_json` `sk_binding` `sk_signature` `sk_listing`

---

## 9. 安全

- 默认禁止未扫描通过的脚本 Skill。  
- `always` 模式仅管理员可设。  
- 权限声明与 Agent 沙箱取交集；声明外工具不可因 Skill 扩大。

---

## 10. 依赖

S11 Agent；S1 加载；S9 RBAC；S8 对象存储。

---

## 10.1 执行链硬约束（ADR-014 D2）

1. **Skill ≠ MCP Tool**；不得向 ToolGateway 注册 Skill 伪工具。  
2. 发布快照 `AgentVersion.skillBindings`（skillVersionId + mode）为运行时唯一来源。  
3. Session 启动安装到工作盘逻辑路径 `skills/<name>/`（WorkspaceFs）；Codex 式渐进读：`auto` 仅索引，`always` 可注入截断正文。  
4. 权限与 Agent 沙箱**求交不扩大**。  
5. 管理面仅 ZIP 入库**不等于**执行链完成；验收见 M-Gap G-SK。

---

## 11–14. 失败 / 观测 / 测试 / 波次

导入失败可重试；钉版本避免漂移。  
测试：zip 导入、签名拒绝、always 权限、绑定后提示词含 Skill 段、**工作区 SKILL.md 可读**。  
波次：W2 核心；W3 签名热更新；W4 市场；**执行链补齐归 M-Gap（ADR-014）**。

---

## 附录 — SKILL.md 最小约定

```markdown
---
name: incident-triage
description: 线上故障排查步骤与检查清单
mode: auto
permissions:
  tools: [kb.search, shell]
  network: none
---
# 故障排查
...
```
