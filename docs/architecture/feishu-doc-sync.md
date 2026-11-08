# 飞书文档 / 知识库同步设计

> 隶属：Tioka 连接器域  
> 借鉴：DeepSeek Harness 生态 `dsh-feishu`（`feishu_doc` / `feishu_wiki` / `feishu_drive` / `feishu_auth`）  
> 原则：**自研实现**，不引入 dsh 运行时；仅复用其能力边界与身份安全策略。

---

## 1. 目标

| 方向 | 说明 |
|---|---|
| PUSH | 本地 Markdown / Knowledge 文档 → 飞书云文档 + Wiki 节点 |
| PULL | 飞书 Wiki/云文档 → Knowledge 入库与索引 |
| TOOL | Agent 经 Runtime Tool 读写飞书文档（受治理授权） |

工程文档（如 `docs/superpowers/specs/*.md`）走 **PUSH**，进入指定飞书知识库空间，供团队在飞书侧阅读。

---

## 2. 组件

```
connector-biz
└── feishu
    ├── auth/FeishuAuthService
    ├── im/FeishuImClient              # 与文档同步隔离
    ├── doc/FeishuDocClient            # docx 创建、Markdown→blocks、读文本
    ├── wiki/FeishuWikiClient          # space、node CRUD、token 解析
    ├── drive/FeishuDriveClient
    └── sync/DocSyncService            # Mapping + SyncJob
```

`knowledge-biz` 的 `KnowledgeSyncApi` **委托** `FeishuDocSyncApi`，不直连飞书 SDK。

---

## 3. 数据模型

### 3.1 FeishuBinding（租户级）

- appId / appSecretRef  
- brand：feishu | lark  
- defaultIdentity：auto | user | app  
- defaultWikiSpaceId / defaultFolderToken  
- oauthScopes  

### 3.2 DocSyncMapping

| 字段 | 说明 |
|---|---|
| sourceType | `GIT_PATH` / `KNOWLEDGE_DOC` / `LOCAL_MD` |
| sourceRef | 如 `docs/superpowers/specs/2026-11-05-....md` |
| targetType | `FEISHU_WIKI_NODE` / `FEISHU_DOCX` |
| spaceId / parentNodeToken / docToken | 飞书侧定位 |
| contentHash | SHA-256，未变则跳过 |
| direction | PUSH / PULL / BIDI |
| lastStatus / lastError / lastSyncAt | 运维 |

### 3.3 SyncJob

幂等键：`mappingId + contentHash + direction`。  
状态：`PENDING → RUNNING → SUCCESS | FAILED`，失败指数退避。

---

## 4. PUSH 流程

1. 触发：管理台、CI、或 `document.updated` MQ。  
2. 读源文件 → 算 hash → 与 mapping 比较。  
3. 无 `docToken`：`wiki.createNode` + `doc.create`（标题=文件名），写回 mapping。  
4. 有 `docToken`：按策略 **全量替换 blocks** 或 **append**（工程文档建议全量替换，避免重复追加）。  
5. Markdown → 飞书 blocks：自研转换器（标题、列表、代码块、表格基础集）；失败则降级为「整篇作为代码块/纯文本」并告警。  
6. 发 `tioka-connector` / `doc.sync.finished`。

---

## 5. PULL 流程

1. 按 mapping 或「同步源配置」列出 Wiki 子树。  
2. 拉取 docx 纯文本或结构化 blocks → 规范化 Markdown。  
3. `DocumentApi` 写入/更新 Knowledge 文档（保留 `externalRef=docToken`）。  
4. 触发 `IngestJob`。  
5. 权限：仅同步 Binding 声明的 space；节点 ACL 变化需可观测。

---

## 6. 与 HCI / Agent

- 同步任务进度可在观测台查看（SyncJob 列表）。  
- Runtime Tool（可选）：`feishu_wiki_list`、`feishu_doc_read`、`feishu_doc_write` — 默认关闭，按 Agent 资产绑定开启。  
- **IM 机器人** 与 **文档同步** 共用 Auth，分用 Client，避免消息与文档凭证纠缠。

---

## 7. 安全

- Secret 仅凭证服务；状态接口只返回「是否已配置」。  
- User token 持久化加密；过期单次刷新重试。  
- 同步范围白名单：path 前缀 + spaceId。  
- 审计：谁触发、同步了哪些 mapping、成功/失败。

---

## 8. 配置本仓库「架构文档 → 飞书」示例

```yaml
# 租户配置示意（非真实密钥）
feishu:
  binding: default
  sync:
    - sourceType: GIT_PATH
      sourceRef: docs/
      targetType: FEISHU_WIKI_NODE
      spaceId: ${FEISHU_WIKI_SPACE_ID}
      parentNodeToken: ${FEISHU_DOCS_PARENT_NODE}
      direction: PUSH
```

执行：`POST /admin-api/connector/feishu/sync/now` 或 CI job。

**说明：** 向真实飞书租户推送前，需在开放平台创建应用并完成 Wiki/Doc 授权；未配置时本地 `docs/` 仍为唯一权威源。

---

## 9. ENG-07 仓库文档单源

- 权威：`docs/architecture`
- 本地飞书镜像：`node scripts/publish-feishu-docs.mjs`（默认输出 `feishu-docs/Tioka/_generated/`，页首「自动生成，勿改」；未配置 `FEISHU_APP_ID/SECRET` 时不推远程）
- 完成度：`node scripts/gen-progress.mjs` → `implementation/progress.md`
