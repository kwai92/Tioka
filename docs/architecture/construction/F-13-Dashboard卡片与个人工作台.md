# F-13 — Dashboard 卡片与个人工作台

- 状态：F2 增强批次（W90+），设计定型；实现未开始
- 负责人：后端 BE-C（卡片注册中心与数据代理，BAS-31）；前端 FE（个人工作台与卡片管理页，BAS-FE-14、BAS-FE-16）
- 工作项：BAS-31（后端 5 人天，F2）、BAS-FE-14（前端 6 人天，F2）、BAS-FE-16（前端 3 人天，F2）
- 决策：[ADR-077](../adr/ADR-077-dashboard-widget-registry.md)（卡片注册中心；SCHEMA/REMOTE 双轨渲染；覆盖 BAS-31、BAS-FE-14、BAS-FE-16）
- 设计依据：[S15](../systems/S15-Foundation基础平台系统.md) 第 23 节（23.1〜23.5）、第 6.3 节（门户与统一入口）、第 11.1 节（清单规则）、第 14.1/14.2 节（接口与事件）、第 18.1/18.2 节（人天）、第 19.3 节（F2 批次）、第 3.1/3.2 节
- 前端依据：[45-FE 前端架构](../enterprise/45-FE-前端架构.md)「统一入口与个人工作台（Foundation，F2 批次）」
- 全局约定：[00-施工总纲](00-施工总纲.md) 第 2 节（分层、标识与时间、错误与响应、事务与并发、配置、测试分层）
- 排期与人天：以 [F-00](F-00-F阶段实施计划.md) 第 1 节第 7 条为准；F2 逐人逐周在 F 阶段结束后另出（[F-01](F-01-并行开发任务包.md) 第 1 节第 6 条）
- 前置：F 阶段 GF 闸门（W89）通过；权限中心（BAS-05）、后端 starter（BAS-08）、门户基座（BAS-FE-10）已交付
- 迁移编号段：Foundation 库。F 阶段自 `V1` 起；**本文件（F2 批次）使用 `V200+` 号段**，与 F 阶段已用的 `V1〜V1xx` 预留间隙，与 Tioka 侧 `V270+` 互不冲突，两边不共用 Flyway 历史表
- 范围说明：应用间授权（ADR-076，BAS-29/30、BAS-FE-15）归 F-12，不在本文；多源同步扩展（ADR-078，BAS-32/33）不在本文

---

## 1. 范围与结论

门户是全公司业务系统的统一入口，由四部分组成：**应用启动台**、**个人工作台**（本文的 Dashboard）、**个人中心**、**消息与待办**，外加全局导航（S15 第 23 节、ADR-077 第 1 条）。本文只覆盖个人工作台的「卡片注册与编排」一条链路。

本文交付：

1. **卡片注册中心**：`fdn_widget` 卡片目录 + `fdn_widget_version` 版本留痕，随应用版本声明并导入（沿用 S15 第 11.1 节清单规则）。
2. **双轨渲染**：`SCHEMA`（配置化）与 `REMOTE`（远程组件），门户侧统一容器渲染。
3. **数据契约与布局**：取数统一 envelope、`fdn_dashboard` 与卡片实例（原位名 `fdn_dashboard_widget`）、默认布局与「重置为默认」。
4. **权限披露与降级**：可见性由授权快照决定（BAS-05/BAS-08），数据标签作为披露上限，单卡失败与 REMOTE 加载失败的降级。
5. **事件与接口**：`fdn.widget.changed`（outbox → RocketMQ `fdn-events`）、目录查询、工作台读写、卡片取数代理。
6. **前端**：个人工作台页（BAS-FE-14）与卡片目录与默认看板管理页（BAS-FE-16）。

关键取舍（照 ADR-077 定案，不改）：

| 取舍 | 结论 | 依据 |
| --- | --- | --- |
| 两类卡片都要支持吗 | 支持 `SCHEMA` 与 `REMOTE` 双轨 | ADR-077 第 3 条 |
| 卡片数据谁来取 | 由 Foundation 统一代理（带用户令牌），不落前端直连各应用接口 | ADR-077 第 7 条、S15 第 23.4 节 |
| 无权限卡片怎么处理 | 既不出现也不占位（不是灰显） | ADR-077 第 6 条、S15 第 23.4 节 |
| 看板布局是否广播 | 不广播（属个人数据） | ADR-077 第 9 条 |
| 是否做低代码图表设计器 | 不做 | ADR-077 第 8 条、S15 第 3.2/23.5 节 |

---

## 2. 卡片注册中心（BAS-31）

### 2.1 卡片定义

卡片是 Foundation 的主数据。`fdn_widget` 一行即一张卡片的定义（S15 第 23.1 节、ADR-077 第 2 条）。

| 属性 | 含义 | 约束 |
| --- | --- | --- |
| 编码 `code` | 卡片在应用内的稳定标识 | 应用内唯一；随版本前进只增不改语义 |
| 名称 `name` | 展示名 | — |
| 所属应用 `app_id` | 卡片归属的子系统 | 引用应用登记（BAS-04） |
| 渲染类型 `render_type` | `SCHEMA` 或 `REMOTE` | 二选一，决定门户侧渲染分支 |
| 数据源类型 `data_source_type` | `rest`（SCHEMA 型）/ `remote-component`（REMOTE 型） | 与 `render_type` 对应 |
| 取数地址 `data_endpoint` | SCHEMA 型只读数据接口 | `render_type=SCHEMA` 时必填 |
| 组件入口 `remote_entry` | REMOTE 型前端包/远程组件入口 | `render_type=REMOTE` 时必填 |
| 参数 schema `param_schema` | 卡片可配参数的 schema | JSON |
| 展示配置 `display_config` | 形态（指标卡/列表/图表/进度/待办）、列、单位、图表类型等 | JSON，仅 SCHEMA 型使用 |
| 所需权限码 `required_perms` | 卡片可见所需的权限码集合 | 可见性过滤依据（第 5 节） |
| 尺寸档 `size_options` | 可选栅格档，如 `1x1`/`2x1`/`2x2` | 栅格单位 |
| 是否可配置 `configurable` | 用户能否改参数/尺寸 | — |
| 数据标签 `data_label` | 披露上限（`PUBLIC`/`INTERNAL`/`CONFIDENTIAL`/`SECRET`） | 沿 ADR-040、ADR-025 |
| 版本 `version` | 清单版本 | 同一应用清单版本只能前进 |
| 状态 `status` | `ACTIVE` / `OFFLINE` | 下架保留 90 天 |

### 2.2 表结构

迁移 `V200`（示意；禁止 `tenant_id`，主键 ULID，时间 `TIMESTAMPTZ`，枚举存 `name()`）：

```sql
-- V200：卡片注册中心（Foundation 库，F2 批次）
CREATE TABLE fdn_widget (
    id               VARCHAR(32)  PRIMARY KEY,                 -- ULID，前缀 wid_
    app_id           VARCHAR(32)  NOT NULL,                    -- 所属应用（子系统）
    code             VARCHAR(128) NOT NULL,                    -- 卡片编码，应用内唯一
    name             VARCHAR(255) NOT NULL,
    icon             VARCHAR(255),
    render_type      VARCHAR(16)  NOT NULL,                    -- SCHEMA / REMOTE
    data_source_type VARCHAR(32)  NOT NULL,                    -- rest / remote-component
    data_endpoint    VARCHAR(512),                             -- SCHEMA 型取数地址
    remote_entry     VARCHAR(512),                             -- REMOTE 型组件入口
    param_schema     JSONB,
    display_config   JSONB,
    required_perms   JSONB        NOT NULL DEFAULT '[]',       -- 所需权限码集合
    size_options     JSONB        NOT NULL DEFAULT '[]',       -- ["1x1","2x1","2x2"]
    configurable     BOOLEAN      NOT NULL DEFAULT false,
    data_label       VARCHAR(16)  NOT NULL DEFAULT 'INTERNAL',
    status           VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',   -- ACTIVE / OFFLINE
    version          VARCHAR(32)  NOT NULL,
    manifest_hash    VARCHAR(64)  NOT NULL,                    -- 清单内容哈希
    sort_order       INT          NOT NULL DEFAULT 0,
    create_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    update_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    create_by        VARCHAR(64)  NOT NULL DEFAULT 'system',
    update_by        VARCHAR(64)  NOT NULL DEFAULT 'system',
    UNIQUE (app_id, code),
    CHECK (render_type IN ('SCHEMA','REMOTE'))
);
CREATE INDEX idx_widget_app  ON fdn_widget (app_id, status);
CREATE INDEX idx_widget_hash ON fdn_widget (app_id, manifest_hash);

-- 版本留痕：每次清单导入落一行，便于审计与回滚前后对比
CREATE TABLE fdn_widget_version (
    id            VARCHAR(32) PRIMARY KEY,                     -- ULID，前缀 wiv_
    widget_id     VARCHAR(32) NOT NULL,                        -- 指向 fdn_widget.id
    app_id        VARCHAR(32) NOT NULL,
    version       VARCHAR(32) NOT NULL,
    manifest_hash VARCHAR(64) NOT NULL,
    change_type   VARCHAR(16) NOT NULL,                        -- ADD / UPDATE / OFFLINE
    snapshot      JSONB       NOT NULL,                        -- 该版本完整卡片定义快照
    create_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    create_by     VARCHAR(64) NOT NULL DEFAULT 'system',
    UNIQUE (widget_id, version)
);
CREATE INDEX idx_widget_version_app ON fdn_widget_version (app_id, version);
```

> `fdn_widget` 不建外键到 `fdn_widget_version`（留痕表只追加，不参与级联）。

### 2.3 注册与升级流程

卡片清单**随应用版本声明并导入**，沿用 S15 第 11.1 节清单规则（同哈希不重复导入、消失的编码标「已下线」保留 90 天后清理、导入幂等）。流程：

| 步骤 | 动作 | 规则 |
| --- | --- | --- |
| 1 声明 | 应用仓库维护卡片清单（随版本发布） | 与权限清单同源；`code` 稳定 |
| 2 导入 | 应用 CI 或启动时调用导入接口 | 校验清单哈希，幂等；同哈希不重复导入 |
| 3 注册 | 新增 `code` 直接生效为 `ACTIVE` | 默认不授予任何角色 |
| 4 升级 | `code` 已存在且内容变化 → 更新定义并写 `fdn_widget_version` | 版本只能前进；回滚应用版本按「新版本」重新导入，审计可见 |
| 5 消失 | 清单里消失的 `code` 标 `OFFLINE` | 保留 90 天后清理；`OFFLINE` 卡片不下发 |
| 6 发事件 | 目录变化发 `fdn.widget.changed` | 经 outbox（第 6 节） |

导入校验（失败即整批拒绝，`BAD_REQUEST`）：

- `render_type=SCHEMA` 必须有 `data_endpoint`；`REMOTE` 必须有 `remote_entry`。
- `required_perms` 里的权限码必须已在权限清单登记（S15 第 11.1 节），否则拒绝整批。
- `size_options` 非空且落在允许档位内。
- `app_id` 必须已登记（BAS-04）。

### 2.4 下架与清理

- **软下架**：清单移除或管理员处置 → `status=OFFLINE`；`OFFLINE` 卡片在下发时被过滤（含工作台里已引用的实例，见第 4.4 节「重置为默认」）。
- **保留 90 天**：`OFFLINE` 行与其 `fdn_widget_version` 保留 90 天，供审计与排查；到期由定时任务清理（不删 `fdn_widget_version` 中仍被审计引用的行）。
- **硬下架只允许管理员**：权限码 `fdn:widget:admin`。

### 2.5 卡片清单的提交方式

以原文为准：卡片清单**随应用版本声明并导入**（S15 第 23.1 节），与权限清单同源、共用 S15 第 11.1 节的导入规则；接口上由「卡片目录导入」这一独立接口承接（S15 第 14.1 节「Dashboard 卡片」组：卡片目录导入与查询）。也就是说：**声明方式随应用版本（与权限清单同批），落地接口独立**，两者不矛盾。

---

## 3. 双轨渲染

两份渲染轨道的分工（S15 第 23.2 节、ADR-077 第 3 条）：

| 轨道 | 应用侧要做什么 | 门户侧 |
| --- | --- | --- |
| `SCHEMA`（配置化） | 只提供只读数据接口 `dataEndpoint`，返回统一 JSON | 内置通用渲染器渲染指标卡、图表（ECharts 折线/柱/饼）、列表、待办、进度等标准形态 |
| `REMOTE`（远程组件） | 发布 Vue 卡片组件（npm 包 / 微前端远程组件） | 按 ADR-054 同一加载机制（推荐无界 wujie）加载，交互与视觉完全由应用控制 |

### 3.1 SCHEMA 型接口契约与生命周期

**接口契约**：`GET {dataEndpoint}`（经门户基座统一代理，见第 6.3 节），返回统一 envelope，至少含 `dataUpdatedAt`；可选 `drillDown`（深链只带业务对象 ID，鉴权沿 ADR-029 第 57 条）。

```jsonc
// ApiResponse.data（总纲 2.3：ApiResponse{code,message,data,traceId}）
{
  "widgetCode": "contract.pending.count",
  "form": "metric",                 // metric | list | chart | progress | todo
  "dataUpdatedAt": "2026-11-13T08:00:00Z",   // 必填
  "value": 12,                      // metric/progress 型
  "items": [ /* list/todo 型 */ ],
  "series": [ /* chart 型：name + data[] */ ],
  "page": { "size": 20, "cursor": "eyJ..." }, // 分页，游标式
  "drillDown": { "id": "c_123", "url": "/contracts/c_123" } // 可选，只带业务对象 ID
}
```

**生命周期**：挂载 → 可见性判定（第 5 节）→ 基座并发拉数（带用户令牌）→ 命中短 TTL 缓存则直出 → 渲染 → 用户点击刷新/进入页面时重取 → 卸载释放。渲染器由 `form` 字段路由到内置形态；未知 `form` 走兜底占位（不阻断其他卡片）。

### 3.2 REMOTE 型接口契约与生命周期

**接口契约**：应用发布一个 Vue 卡片组件入口（`remote_entry`），接受基座下发的 props：`{ widgetCode, params, size, locale, theme }`，并提供一个 `refresh()` 约定。**必须经前端包提供的通信接口取得令牌与用户上下文，不得直接访问基座全局对象**（ADR-054 第 4 条；S15 第 23.2 节）。

**生命周期**：门户按 ADR-054 同一加载机制（wujie：iframe 承载 JS 沙箱、Web Component 承载 DOM）挂载 → 保活切换 → 卸载。深链、保活切换、基座内加载须通过子应用接入自检三项（S15 第 6.3 节、BAS-FE-11）。

### 3.3 沙箱、样式隔离与错误边界

| 项 | 设计 | 依据 |
| --- | --- | --- |
| 沙箱 | REMOTE 组件在 wujie 的 iframe 沙箱内运行，全局变量天然隔离 | ADR-054 第 4 条、S15 第 6.3 节 |
| 样式隔离 | Web Component 承载 DOM，样式不外泄也不受基座影响；不强制共享 Vue/Element Plus 实例（隔离优先） | S15 第 6.3 节 |
| 通信 | 只允许经前端包通信接口：基座→卡片下发用户/主题/语言；卡片→基座发「打开某功能」「显示通知」；事件名与载荷在前端包统一定义 | S15 第 6.3 节 |
| 错误边界 | SCHEMA 型渲染异常与 REMOTE 型加载异常各自被单卡错误边界捕获，只渲染该卡错误态，不影响同看板其他卡片 | ADR-077 第 7 条 |
| 降级 | REMOTE 卡片加载失败降级为新窗口打开深链（与 ADR-054 子应用降级一致） | ADR-077 第 7 条 |

---

## 4. 数据契约与布局

### 4.1 取数接口契约

- 取数**由门户基座统一代理**（带用户令牌）并发拉取（S15 第 23.4 节、ADR-077 第 7 条）；前端不直接请求各应用接口。
- 统一 envelope 见第 3.1 节；响应外层仍是 `ApiResponse{code,message,data,traceId}`（总纲 2.3）。
- **分页**：游标式（`page.cursor`），列表/待办型卡片使用；不接受页码式以避免并发刷新时错位。
- **时间窗参数**：`timeWindow=7d|30d|90d|custom`，`custom` 附 `from`/`to`（ISO-8601 UTC）。参数落在 `fdn_dashboard_widget.params`。
- **鉴权语义**：卡片数据一律按「当前用户」身份在被调应用内鉴权；跨应用委派（OBO）场景按 S15 第 22 节走用户委派。

### 4.2 缓存、刷新、超时与降级

| 项 | 设计 | 依据 |
| --- | --- | --- |
| 缓存 | 基座侧短 TTL 缓存（默认 60s），键含用户、卡片编码与参数 | ADR-077 第 7 条 |
| 刷新 | 进入工作台自动拉取一次 + 手动刷新按钮（单卡与整板） | S15 第 23.4 节 |
| 并发 | 看板内多卡并发拉取，单卡互不阻塞 | ADR-077 第 7 条 |
| 超时 | 单卡请求超时（默认 3s）即降级为单卡错误态 + 重试 | ADR-077 第 7 条 |
| 降级占位 | 失败/超时只影响该卡：错误态 + 「重试」；不拖垮整个看板 | S15 第 23.4 节 |

配置项（`tioka.fdn.widget.*`，`@ConfigurationProperties`，带默认值，总纲 2.5）：`cache-ttl=60s`、`fetch-timeout=3s`、`max-concurrency=8`、`offline-retention-days=90`。

### 4.3 个人工作台布局与表结构

- `fdn_dashboard`：所有者（用户 / 角色 / 组织）、名称、是否默认、是否个人自建（S15 第 23.3 节、ADR-077 第 5 条）。
- **卡片实例**：看板 → 卡片引用的实例，含尺寸档、栅格坐标、排序、参数。**原位命名为 `fdn_dashboard_widget`**（S15 第 23.3 节、ADR-077 第 5 条）；本文件另记作「卡片实例」（即任务书中的 `fdn_widget_instance`，二者指同一实体，施工以原文命名 `fdn_dashboard_widget` 为准），并补 `visible` 承载用户可见性开关。

```sql
-- V201：工作台布局（Foundation 库，F2 批次）
CREATE TABLE fdn_dashboard (
    id          VARCHAR(32) PRIMARY KEY,                     -- ULID，前缀 dsh_
    owner_type  VARCHAR(16) NOT NULL,                        -- USER / ROLE / ORG
    owner_id    VARCHAR(64) NOT NULL,                        -- 用户十进制字符串 / 角色码 / 组织单元编码
    name        VARCHAR(255) NOT NULL,
    is_default  BOOLEAN     NOT NULL DEFAULT false,          -- 默认看板（可下发）
    is_personal BOOLEAN     NOT NULL DEFAULT false,          -- 用户自建
    status      VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    version     INT         NOT NULL DEFAULT 1,              -- 乐观锁
    create_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    create_by   VARCHAR(64) NOT NULL DEFAULT 'system',
    update_by   VARCHAR(64) NOT NULL DEFAULT 'system',
    CHECK (owner_type IN ('USER','ROLE','ORG'))
);
-- 一个用户在任一时刻只有一块默认看板
CREATE UNIQUE INDEX uq_dashboard_user_default
    ON fdn_dashboard (owner_id) WHERE owner_type = 'USER' AND is_default;

CREATE TABLE fdn_dashboard_widget (                          -- 卡片实例
    id           VARCHAR(32)  PRIMARY KEY,                   -- ULID，前缀 dwi_
    dashboard_id VARCHAR(32)  NOT NULL,                      -- 指向 fdn_dashboard.id
    app_id       VARCHAR(32)  NOT NULL,
    widget_code  VARCHAR(128) NOT NULL,                      -- 引用 fdn_widget (app_id, code)
    size         VARCHAR(8)   NOT NULL DEFAULT '1x1',        -- 尺寸档
    grid_x       INT          NOT NULL DEFAULT 0,            -- 栅格列
    grid_y       INT          NOT NULL DEFAULT 0,            -- 栅格行
    sort_order   INT          NOT NULL DEFAULT 0,
    params       JSONB,                                      -- 卡片参数（时间窗等）
    visible      BOOLEAN      NOT NULL DEFAULT true,         -- 用户可见性（个人开关）
    create_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    update_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    create_by    VARCHAR(64)  NOT NULL DEFAULT 'system',
    update_by    VARCHAR(64)  NOT NULL DEFAULT 'system',
    UNIQUE (dashboard_id, app_id, widget_code)
);
CREATE INDEX idx_dashboard_widget_dash ON fdn_dashboard_widget (dashboard_id, sort_order);
```

> 引用 `fdn_widget` 用逻辑引用（`app_id + widget_code`），不建物理外键：卡片目录随应用版本演进，硬外键会在下架/清理时阻塞。`(dashboard_id, app_id, widget_code)` 唯一保证同一卡片在看板内只出现一次。
> **布局属个人数据，`fdn_dashboard*` 的变更不广播**（ADR-077 第 9 条）。

### 4.4 默认布局与「重置为默认」

| 场景 | 行为 |
| --- | --- |
| 新用户首次进入 | 取「匹配本人的默认看板」：按 用户 → 角色 → 组织 优先级解析（`owner_type`），命中后**物化**为该用户的 `USER` 看板 |
| 管理员/角色下发默认 | 维护 `owner_type=ROLE/ORG`（`is_default=true`）的默认看板（权限码 `fdn:widget:admin`） |
| 重置为默认 | 重新解析默认看板并覆盖当前 `USER` 看板的卡片实例（新增/移除/位置/尺寸/参数），保留用户自建的非默认看板 |
| 卡片被下架 | 默认布局中已 `OFFLINE` 的卡片在渲染前被过滤（第 5 节），不产生占位 |
| 用户自建 | `is_personal=true` 的 `USER` 看板自由增删卡片、拖拽与尺寸切换 |

---

## 5. 权限、披露与降级

### 5.1 可见性（来自快照）

卡片可见性**由授权快照决定**（BAS-05/BAS-08），不硬编码：

- 卡片按其 `required_perms` 过滤：快照中不含所需权限码时，卡片**既不出现也不占位**（ADR-077 第 6 条、S15 第 23.4 节）。
- 过滤在**服务端下发前**完成（目录查询、工作台读取、取数代理三处一致），前端只按服务端结果渲染。
- 功能开关（S15 第 6.1 节）停用的模块，其卡片同样从快照剔除，不下发。

### 5.2 披露上限（数据标签）

数据标签是「可发送到前端的披露上限」（ADR-077 第 6 条、S15 第 23.4 节）：

| 卡片 `data_label` | 可否披露到工作台 |
| --- | --- |
| `PUBLIC` / `INTERNAL` | 允许 |
| `CONFIDENTIAL` | 由空间配置决定（沿 ADR-040 数据标签与 ADR-025 去向许可） |
| `SECRET` | 不进卡片（一律不披露） |

取数代理在返回前按被调应用的数据标签与去向许可二次校验；越限时返回**脱敏/降级占位**而非原始数据。

### 5.3 应用不可用与取数失败的降级

| 故障 | 表现 |
| --- | --- |
| 单卡取数失败 / 超时 | 该卡显示错误态 + 「重试」，不影响同看板其他卡片（第 4.2 节） |
| SCHEMA 渲染异常 | 单卡错误边界捕获，渲染兜底占位 |
| REMOTE 组件加载失败 | 降级为「在新窗口打开」深链（ADR-054 第 4 条） |
| 应用整体不可用 | 该应用所有卡片一并降级；门户与其他应用卡片不受影响 |

### 5.4 与快照失效、`fdn.widget.changed` 的关系

- **可见性**依赖授权快照：授权变化发 `fdn.authz.changed`，starter 失效本地缓存（S15 第 11.3 节）；工作台下次读取即按新快照过滤。
- **目录变化**发 `fdn.widget.changed`，门户工作台缓存按事件失效（S15 第 14.2 节）；事件丢失时依靠短 TTL 兜底。
- **失败方向**：快照不可用且缓存过期时**拒绝**而不是放行（S15 第 11.3 节「降级拒绝」精神）——卡片宁可不下发，也不越权下发。

---

## 6. 事件与接口

### 6.1 事件 `fdn.widget.changed`

- 含义：Dashboard 卡片目录变化（ADR-077 第 9 条、S15 第 14.2 节）。
- 通道：写库后经 **outbox** 投递到 RocketMQ 主题 **`fdn-events`**（可同时投 Webhook）；**事件体只带 ID、版本与变化类型**，订阅方需要详情时回查接口，避免敏感字段在消息里扩散（S15 第 14.2 节）。
- 主要订阅方：**门户工作台缓存**。
- 载荷（示意）：

```jsonc
{
  "event": "fdn.widget.changed",
  "appId": "app_xxx",
  "widgetIds": ["wid_..."],       // 仅 ID
  "changeType": "ADD|UPDATE|OFFLINE",
  "version": "1.3.0",
  "occurredAt": "2026-11-13T08:00:00Z"
}
```

- 看板布局（`fdn_dashboard` / `fdn_dashboard_widget`）**不广播**。

### 6.2 接口清单

| 接口 | 方法/路径（示意） | 调用方 | 权限码 | 说明 |
| --- | --- | --- | --- | --- |
| 卡片目录导入 | `POST /api/fdn/widgets/manifest` | 应用 CI / 启动 | 服务账号 | 幂等，要求 `Idempotency-Key` |
| 卡片目录查询 | `GET /api/fdn/widgets` | 管理台、门户 | 管理台 `fdn:widget:admin`；门户内部 | 按应用/状态/权限过滤 |
| 卡片上下架 | `PATCH /api/fdn/widgets/{code}/status` | 管理台 | `fdn:widget:admin` | 软下架/上线 |
| 我的工作台读取 | `GET /api/fdn/dashboards/mine` | 门户 | 登录用户 | 物化默认看板 + 按快照过滤 |
| 工作台写入 | `PUT /api/fdn/dashboards/{id}` | 门户 | 登录用户（属主） | 卡片实例/位置/尺寸/排序/可见性 |
| 默认看板下发 | `POST /api/fdn/dashboards/default` | 管理台 | `fdn:widget:admin` | 维护 `ROLE/ORG` 默认板 |
| 重置为默认 | `POST /api/fdn/dashboards/{id}/reset` | 门户 | 登录用户 | 覆盖为默认布局 |
| 卡片取数代理 | `GET /api/fdn/widgets/{code}/data` | 门户 | 登录用户 | 带用户令牌；统一 envelope |
| 卡片清单契约 | `GET /api/fdn/widgets/{code}` | 门户 | 登录用户 | 单卡定义（已过滤） |

所有接口在 OpenAPI 文件发布，接入方用契约测试校验（S15 第 14.1 节）；写接口要求 `Idempotency-Key`。

### 6.3 取数代理的边界（是否由 Foundation 代理，以原文为准）

**是**：卡片数据由门户基座统一代理请求（带用户令牌）并发拉取（ADR-077 第 7 条、S15 第 23.4 节），§14.1 也列「卡片数据代理」为 Foundation 接口。理由（ADR-077 备选）：前端直连各应用接口会绕过统一鉴权与披露控制、令牌与跨域管理分散、无法统一降级。

边界：

- 这只是**卡片取数的窄代理**，不是通用应用网关；不改变「不做集中式应用网关」的定案（ADR-076 第 10 条、ADR-051 第 5 条）。
- 被调应用侧仍按「当前用户」身份在自身内鉴权；取数代理只负责统一令牌透传、披露上限校验、缓存与降级。
- 代理请求记入审计：调用方记「谁代表谁取了哪张卡」，被调应用记「谁取了我」（S15 第 15 节、ADR-072/074 精神）。

---

## 7. 前端（BAS-FE-14、BAS-FE-16）

### 7.1 页面与组件

| 工作项 | 页面/组件 | 要点 |
| --- | --- | --- |
| BAS-FE-14（6） | 个人工作台页 | 栅格容器、卡片容器（双轨渲染）、布局与排序（拖拽 + 尺寸切换）、配置抽屉（参数/尺寸/可见性）、重置为默认 |
| BAS-FE-14 | 卡片容器（SCHEMA 分支） | 内置渲染器：指标卡、列表、图表（ECharts 折线/柱/饼）、待办、进度；统一 loading / 空态 / 错误态 / 重试 |
| BAS-FE-14 | 卡片容器（REMOTE 分支） | 按 ADR-054 同一加载机制挂载；加载失败降级为新窗口打开 |
| BAS-FE-14 | 单卡错误边界 | 单卡失败不拖垮整板 |
| BAS-FE-16（3） | 卡片管理页 | 卡片目录注册/查询/下架、默认布局配置（`ROLE/ORG`） |
| BAS-FE-16 | 卡片数据代理联调 | 与 BAS-31 取数代理联调（envelope、分页、时间窗、降级） |

### 7.2 接口依赖与权限码

| 前端调用 | 依赖接口 |
| --- | --- |
| 工作台读取/写入/重置 | `GET /api/fdn/dashboards/mine`、`PUT`、`POST .../reset` |
| 卡片渲染取数 | `GET /api/fdn/widgets/{code}/data`、`GET /api/fdn/widgets/{code}` |
| 卡片管理页 | `GET /api/fdn/widgets`、`POST /api/fdn/widgets/manifest`、`PATCH .../status`、`POST /api/fdn/dashboards/default` |

权限码：

| 权限码 | 用途 |
| --- | --- |
| `fdn:widget:admin` | 卡片目录与默认看板管理（ADR-077 第 6 条） |
| （卡片可见性） | 由各卡片 `required_perms` 决定，非固定权限码 |

### 7.3 验收

| 项 | 验收 |
| --- | --- |
| 双轨渲染 | 同一工作台内 SCHEMA 卡与 REMOTE 卡都能渲染 |
| 布局编排 | 拖拽与尺寸切换后刷新仍保持；「重置为默认」恢复默认布局 |
| 可见性 | 无权限卡片完全不出现（无占位）；`SECRET` 卡片不展示 |
| 降级 | 单卡失败只显示错误态 + 重试；REMOTE 加载失败出新窗口深链 |
| 保活与深链 | REMOTE 卡片基座内加载、深链直达、保活切换三项通过（BAS-FE-11 自检） |

---

## 8. 测试与闸门

测试分层按总纲第 2.6 节：单元 `*Test`、集成 `*IT`（Testcontainers）、闸门 `gate/GfWidgetGateIT`（`@Tag("gate")`）。

### 8.1 单元（后端 + 前端）

| 用例 | 断言 |
| --- | --- |
| Schema 渲染 | 各 `form`（metric/list/chart/progress/todo）正确路由到渲染器；未知 `form` 走兜底占位 |
| 错误边界 | 单卡渲染/取数异常被捕获，其他卡片继续渲染 |
| 可见性过滤 | `required_perms` 不满足的卡片从下发结果中剔除（无占位） |
| 披露过滤 | `SECRET` 不出现在结果；`CONFIDENTIAL` 按空间配置 |
| 清单导入 | 同哈希不重复导入；内容变化生成新版本；消失编码标 `OFFLINE`；校验失败整批拒绝 |
| 重置为默认 | 覆盖用户看板实例并保留自建板 |

### 8.2 集成（Testcontainers + 前端组件测试）

- 后端：Testcontainers PostgreSQL 16 + Redis；每个用例 `TestFixtures.newWorkspace()` 建独立数据，只断言本用例数据（总纲 2.6、ENG-01）。
- 前端：卡片容器组件测试覆盖 SCHEMA/REMOTE 两分支、loading/空态/错误态、重置交互。

### 8.3 闸门 `GfWidgetGateIT`

`@Tag("gate")`，双实例 + 容器；对应 BAS-31 验收：

| 断言 | 依据 |
| --- | --- |
| 无权限卡片不下发（不出现、不占位） | S15 第 18.1 节 BAS-31 验收 |
| `SECRET` 不披露 | S15 第 18.1 节 BAS-31 验收 |
| 清单导入幂等、消失编码 `OFFLINE` 保留 90 天 | S15 第 23.1 节 |
| 目录变化 5s 内发出 `fdn.widget.changed`、门户缓存失效 | S15 第 14.2 节 |
| 单卡超时/失败只影响该卡 | ADR-077 第 7 条 |

### 8.4 端到端

登录 → 个人工作台按权限显示卡片 → `REMOTE` 卡片加载 → 应用不可用时该卡降级为「在新窗口打开」深链。

### 8.5 完成标准

1. 8.1〜8.4 全部用例通过；`GfWidgetGateIT` 在 CI 独立任务转绿。
2. 双轨渲染、工作台编排与重置、单卡降级在端到端用例中可复现。
3. 无任何 `tenant_id`；主键 ULID、时间列为 `TIMESTAMPTZ`（ArchUnit / 迁移检查）。
4. OpenAPI 已更新，前端类型已重新生成（总纲 2.7）。

---

## 9. 不做

不做拖拽式低代码图表设计器（卡片形态由应用声明或门户内置渲染器覆盖）；不做卡片之间的联动钻取编排（先用深链跳转，跨卡片联动留待需要时再议）（ADR-077 第 8 条、S15 第 3.2/23.5 节）。另：

- 不做通用应用网关（仅卡片取数窄代理，第 6.3 节）。
- 不把卡片渲染与 Chat/Runtime 的 Part 渲染体系混为一谈——Part 属 Chat/Runtime，Widget 属门户工作台（45-FE）。
- 不做卡片数据的多租户隔离；隔离靠 `workspace_id` + `owner_id`（ADR-029 第 60 条，禁止 `tenant_id`）。

---

## 与其他文档的关系

| 文档 | 关系 |
| --- | --- |
| [ADR-077](../adr/ADR-077-dashboard-widget-registry.md) | 本文的权威决策：卡片注册中心、双轨渲染、覆盖 BAS-31/BAS-FE-14/BAS-FE-16；本文不改决策与覆盖范围 |
| [S15](../systems/S15-Foundation基础平台系统.md) 第 23 节 | 本文的设计权威（23.1 注册中心、23.2 双轨渲染、23.3 契约与布局、23.4 权限披露与降级、23.5 不做）；第 14.1/14.2 节给接口与事件，第 18.1/18.2 节给人天，第 19.3 节给批次与前置 |
| [ADR-054](../adr/ADR-054-engineering-base-subsystem-microfrontend.md) | REMOTE 卡片沿用其微前端加载与通信机制（wujie、前端包通信接口、降级为新窗口打开） |
| [ADR-029](../adr/ADR-029-foundation-platform.md) 第 57、60、81 条 | 深链鉴权（第 57 条）、禁止 `tenant_id`（第 60 条）、F2 增强批次立项（第 81 条） |
| [ADR-025](../adr/ADR-025-data-label-outbound-permission.md) / [ADR-040](../adr/ADR-040-dataset-policy-and-labels.md) | 数据标签与去向许可，决定 `CONFIDENTIAL`/`SECRET` 的披露上限 |
| [F-00](F-00-F阶段实施计划.md) / [F-01](F-01-并行开发任务包.md) | 排期与人天权威；F2 批次（W90+）前置为 GF 闸门通过；F2 逐人逐周在 F 阶段后另出 |
| [00-施工总纲](00-施工总纲.md) 第 2 节 | 分层、标识与时间、错误与响应、事务与并发、配置、测试分层等全局约定 |
| [F-12](F-12-应用间授权.md) | 应用间授权（ADR-076，BAS-29/30、BAS-FE-15）归其所有，不在本文 |
| [45-FE 前端架构](../enterprise/45-FE-前端架构.md) | 门户与个人工作台的定位；卡片渲染与 Part 渲染体系的边界 |
| [M1-01](M1-01-权限与身份联邦.md) | 本文头部体例参考；授权快照（BAS-05/BAS-08）是可见性来源 |
