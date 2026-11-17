# F-11 — 部署与 Tioka 接入与 GF 闸门（Foundation）

- 状态：施工设计（2026-11-13）。设计权威是 [S15 Foundation 基础平台系统](../systems/S15-Foundation基础平台系统.md) 第 9、12、16、17 节与 [ADR-051〜054](../adr/)；排期权威是 [F-00 F 阶段实施计划](F-00-F阶段实施计划.md)，冲突时以 F-00 为准
- 负责人：BE-B（BAS-21 部署与交付、BAS-22 GF 闸门收口）；BE-A（BAS-20 Tioka 接入）；BE-C（BAS-28 合同业务系统迁入）
- 工作项：BAS-21 部署与交付、BAS-20 Tioka 接入 Foundation、BAS-28 合同业务系统迁到 Foundation、BAS-22 GF 端到端闸门与闸门基座收口（按实施顺序）
- 约束：ADR-051〜054、ADR-026、[M4-06](M4-06-企业交付与审计.md) 第 0.1/0.2/5 节、ADR-029 第 60、69、76、80、81 条；F-00 §5 实现约定 1〜8；F-01 §6 实现硬约束。**硬约束：单企业独立部署，禁止 `tenant_id` / `sys_tenant`（ADR-029 第 60 条）；两仓库迁移号段互不干扰；不引入未经 ADR 许可的新中间件**；配置前缀 `foundation.*`；时间列 `TIMESTAMPTZ`、DO 字段 `LocalDateTime`（语义 UTC）；传输全部 HTTPS
- 排期与人天：以 [F-00](F-00-F阶段实施计划.md) §3.1 原文为准——**BAS-21 部署 3 人天（BE-B，W84–W85）**、**BAS-20 Tioka 接入 9 人天（BE-A，W84–W86）**、**BAS-28 合同业务系统迁到 Foundation 5 人天（BE-C，W86–W87）**、**BAS-22 GF 端到端闸门 3 人天（BE-B，W87）**，合计 20 人天（含编码、测试与评审）。名称与人天以 F-00 §3.1 原文为准；F-00 §6 中承接 GF 闸门的是 BAS-22，BAS-28 的范围是合同业务系统迁入
- 前置：[F-00](F-00-F阶段实施计划.md) §2 R1〜R8（R1 G4 通过、R3 闸门基座含 Keycloak 与 PostgreSQL 容器、R6 合同业务系统按 §19.2 过渡约定实现、R8 前端包已合入）；FND-11（[M4-06](M4-06-企业交付与审计.md) §5，M4）已交付 Helm 与离线包，BAS-21 在其上补 Foundation 与 Keycloak 部分（§5.1「预留」）；BAS-20 依赖 BAS-08（starter）与 BAS-14（离职联动）；BAS-28 依赖 BAS-08、BAS-27；BAS-22 依赖 BAS-14、BAS-20
- 迁移编号段：**Foundation 库自 V1 起**（自有 Flyway 历史表）；**Tioka 侧用 V270 起**——V270 由 [F-02](F-02-工程底座与骨架.md) BAS-26 预留，本文 BAS-20 用 **V271/V272**；BAS-21/28/22 本身不新增 Tioka 号段（Foundation 侧表结构由 BAS-01〜10 建立，本文只做部署与初始化）
- 设计依据：S15 §9（认证）、§12.1（接入步骤）、§16（部署与运维）、§17（对现有设计的影响）、§19.2（过渡约定）；体例参考 [M1-01](M1-01-权限与身份联邦.md)、[F-02](F-02-工程底座与骨架.md)、[F-04](F-04-账号与登录与离职联动.md)

---

## 1. 结论

1. **BAS-21 在 FND-11 的 Compose / Helm / 离线包上做加法**：新增 Foundation 服务、Foundation 管理台与门户静态资源、Keycloak 三个部署单元，与 Tioka 共用 PostgreSQL、Redis、RocketMQ、对象存储（不同库、键与主题加 `fdn` 前缀），中间件与镜像全部沿用 FND-11 已在 M4 登记的离线清单（约束 F43），不新增中间件。
2. **BAS-20 是一次可回退的接线**：Tioka 保留 `tioka.foundation.mode=local|foundation` 双模式（开关在 [F-02](F-02-工程底座与骨架.md) BAS-26 已定义），Foundation 模式下 `Principal` 改读授权快照、管理页跳转 Foundation、系统级用户与角色迁出、FND-09/FND-10 的 Tioka 实现删除；回退 = 切回 `local` 模式，不回滚数据库。
3. **BAS-28 把合同业务系统从 M3 的过渡方案迁到 Foundation**：用户、区域授权、角色迁到 Foundation 的组织、`region` 维度与应用级角色，逐个用户可见的合同集合迁移前后一致，过渡期的「用户 → 区域」表停用。
4. **BAS-22 收口闸门**：把 F-00 §6.1 的 12 条 `Gf*GateIT` 在 Foundation 闸门基座上跑通，其中 `GfFoundationE2EIT` 连续 3 天绿；W88–W89 在 Foundation 模式重跑 G0〜G4、在本地模式重跑 G1 与 G4。
5. 本文只用文字、表格与代码块；未给出的细节按 S15 与 F-00 合理推导并保持克制。

---

## 2. 迁移号段与仓库边界

| 项 | 规则 |
| --- | --- |
| 仓库 | `tioka-foundation`（[ADR-051](../adr/ADR-051-foundation-platform.md) 第 1 条）；Tioka 侧只改 BAS-20/26 与前端挂接（[F-01](F-01-并行开发任务包.md) §1） |
| Foundation 库号段 | 自 **V1** 起，自有 Flyway 历史表；表结构由 BAS-01〜10 建立，本文不新增 |
| Keycloak 库 | 由 Keycloak 自身 schema 管理（非 Flyway），与 Foundation 库可同实例不同库 |
| Tioka 侧号段 | **V270 起**：V270（BAS-26 预留）、**V271（BAS-20 前半）**、**V272（BAS-20 后半）**；与 M4 段 V220〜V269 不冲突 |
| 边界 | Foundation 包不得改 Tioka 业务代码；Tioka 包不得改 Foundation 中台实现；跨仓库只经已发布的 starter / OpenAPI / 事件契约（[F-01](F-01-并行开发任务包.md) §7.2） |

---

## 3. BAS-21 部署与交付（P0，3 人天，BE-B，W84–W85，包 PF-B06）

### 3.1 形态与部署单元

| 部署单元 | 内容 | 副本 | 来源 |
| --- | --- | --- | --- |
| Foundation 服务 | 一个 Spring Boot 应用：主数据 · 权限中心 · 公共服务；**无状态、多实例**，同步与对账任务靠 Redis 锁单实例执行 | 生产 ≥2 | S15 §4、§16 |
| 管理台与门户静态资源 | Vue 3 + Element Plus 单页；门户与应用启动台、个人中心、消息待办同一工程的另一入口 | 按需 | S15 §4、§16 |
| Keycloak | 一个 realm（默认 `enterprise`），登录与令牌；会话用其内置分布式缓存 | 生产 ≥2 | S15 §16、ADR-053 第 7 条 |
| 中间件 | PostgreSQL、Redis、RocketMQ、对象存储，与 Tioka 共用（不同库、`fdn` 前缀） | — | S15 §16、ADR-053 第 7 条 |

启动顺序固定为 **Keycloak → Foundation（初始化 realm、内置应用与三员角色）→ 各应用**（S15 §16）。

### 3.2 容器镜像与编排（Compose / Helm）

镜像复用 FND-11 的 Dockerfile 形态：`eclipse-temurin:17-jre` + OTel Java Agent + `-Duser.timezone=UTC`（[deploy/Dockerfile](../../../deploy/Dockerfile)）；Foundation 用同一父工程与 agent，新增 `deploy/Dockerfile.foundation`。

| 编排 | 位置 | 内容 |
| --- | --- | --- |
| Docker Compose | `deploy/compose.yml`（既有）叠加 `deploy/compose.foundation.yml`（新增） | PostgreSQL（pgvector）、Redis、RocketMQ（namesrv+broker）、MinIO、Keycloak、Foundation、Tioka 一套起；小部署一个进程全开 |
| Helm | `deploy/helm/tioka`（既有，FND-11）在值文件填入 Foundation 与 Keycloak 占位；新增 `deploy/helm/foundation` 子 chart | Foundation Deployment（≥2 副本）、管理台与门户 Deployment（nginx 静态）、迁移 Job、Keycloak Deployment（≥2 副本）、Ingress、ConfigMap / Secret、HPA |

```text
deploy/
├── compose.yml                     # 既有：Tioka 与中间件
├── compose.foundation.yml          # 新增：Foundation、Keycloak 生产剖面（与 compose.yml 叠加）
├── Dockerfile                      # 既有：Tioka 镜像
├── Dockerfile.foundation           # 新增：Foundation 服务镜像（同一父工程 + OTel agent）
├── init-pgvector.sql               # 既有
├── init/foundation-db.sql          # 新增：建 foundation、keycloak 库与账号（属主 / 应用）
├── helm/
│   ├── tioka/                      # 既有（FND-11）：填 Foundation 与 Keycloak 占位
│   └── foundation/                 # 新增：Foundation + Keycloak 子 chart
│       ├── Chart.yaml
│       ├── values.yaml             # 与 compose.yml 共用配置项名
│       └── templates/
│           ├── foundation-deployment.yaml       # 服务（≥2 副本）
│           ├── foundation-ui-deployment.yaml    # 管理台与门户（nginx）
│           ├── migration-job.yaml               # Flyway 迁移 Job（属主账号）
│           ├── keycloak-deployment.yaml         # Keycloak（≥2 副本）
│           ├── ingress.yaml                     # sso./foundation. 域名与证书
│           ├── configmap.yaml
│           ├── secret.yaml
│           └── hpa.yaml
└── offline/manifest                # 既有（约束 F43）：BAS-21 补登记 Foundation 与 Keycloak 镜像
```

### 3.3 中间件清单

| 中间件 | 用途 | 与 Tioka 的关系 | 约束 |
| --- | --- | --- | --- |
| PostgreSQL 16（含 pgvector） | Foundation 库（权限快照、字典参数、审计）与 Keycloak 库（Keycloak 自身 schema） | 可共用实例、不同库 | 沿用 FND-11 chart；无新中间件 |
| Redis 7 | 快照缓存、Redis 锁（单实例任务）、限流 | 共用实例 | 键前缀 `fdn` |
| RocketMQ 5 | 事件主题 `fdn-events`（outbox 投递，可同时投 Webhook HMAC） | 共用 broker | 主题前缀 `fdn`；事件体只带 ID、版本、变化类型 |
| 对象存储（MinIO / 客户 OSS） | 审计与登录日志归档、文件服务 | 共用实例 | bucket 前缀 `fdn-`；经 `ObjectStorageAdapter`（ADR-026） |
| Keycloak 24 | 登录协议与令牌 | 新增部署单元 | 一个 realm；Foundation 经管理接口推送 |

> 以上全部来自 S15 §16 与 ADR-053 第 7 条；**不引入任何未经 ADR 许可的中间件**（无调度中心、无图数据库、无多租户组件）。

### 3.4 数据库初始化与迁移执行

- **初始化**（首次启动，S15 §16）：建 realm `enterprise`；建 Foundation 与门户两个应用；建三员（系统管理员、安全管理员、审计管理员，互斥）内置角色；建应急管理员（默认停用，一次性启用口令写入启动日志，延续 FND-02 随机初始密码思路）。
- **迁移执行**：沿用 [M4-06](M4-06-企业交付与审计.md) §0.1 的账号分离——Foundation 库用属主账号 `fdn_owner` 由**独立迁移 Job** 执行 Flyway（应用等 Job 完成后启动），运行时用 `fdn_app`（对审计类表只有 INSERT、SELECT）；开发与 Compose 环境可只用一个账号。
- Keycloak 库由 Keycloak 自己初始化与升级，Foundation 不写 Keycloak 库。

```sql
-- deploy/init/foundation-db.sql（片断）
CREATE DATABASE foundation;
CREATE DATABASE keycloak;
CREATE ROLE fdn_owner LOGIN PASSWORD :'owner_pwd';
CREATE ROLE fdn_app   LOGIN PASSWORD :'app_pwd';
GRANT ALL ON DATABASE foundation TO fdn_owner;
GRANT CONNECT ON DATABASE foundation TO fdn_app;
```

### 3.5 配置清单与环境变量

所有配置项在 `foundation.*` 下（[F-02](F-02-工程底座与骨架.md) §配置），Compose 与 Helm 共用同一套键名；Spring Boot 宽松绑定使 `foundation.keycloak.base-url` ↔ `FOUNDATION_KEYCLOAK_BASE_URL`。

| 配置项 | 默认 | 映射环境变量 | 说明 |
| --- | --- | --- | --- |
| `foundation.server.base-url` | — | `FOUNDATION_SERVER_BASE_URL` | 对外地址，用于回调与深链 |
| `foundation.keycloak.base-url` | — | `FOUNDATION_KEYCLOAK_BASE_URL` | Keycloak 管理接口地址 |
| `foundation.keycloak.realm` | `enterprise` | `FOUNDATION_KEYCLOAK_REALM` | 单一 realm |
| `foundation.keycloak.admin-client-id` / `-secret` | — | `..._ADMIN_CLIENT_ID` / `..._ADMIN_CLIENT_SECRET` | 服务账号（`client_credentials`） |
| `foundation.db.url` / `.user` / `.password` | — | `FOUNDATION_DB_URL` / `..._USER=fdn_app` / `..._PASSWORD` | 运行时账号 |
| `foundation.redis.host` / `.prefix` | — / `fdn` | `FOUNDATION_REDIS_HOST` / `..._PREFIX` | 键前缀 |
| `foundation.mq.name-server` / `.topic` | — / `fdn-events` | `FOUNDATION_MQ_NAME_SERVER` / `..._TOPIC` | 事件主题 |
| `foundation.storage.endpoint` / `.bucket-prefix` | — / `fdn-` | `FOUNDATION_STORAGE_ENDPOINT` / `..._BUCKET_PREFIX` | 对象存储 |
| `foundation.sso.public-url` | — | `FOUNDATION_SSO_PUBLIC_URL` | OIDC issuer，须与令牌 `iss` 完全一致 |
| `foundation.authz.snapshot-cache-ttl` | `5m` | `FOUNDATION_AUTHZ_SNAPSHOT_CACHE_TTL` | starter 快照缓存 TTL（S15 §11.3） |
| `foundation.audit.retention-days` | `180` | `FOUNDATION_AUDIT_RETENTION_DAYS` | 在线保留；SEC 类按 [ADR-072](../adr/ADR-072-audit-event-model-coverage.md) 在线 400 天、归档 3 年 |
| `foundation.secret.master-key-ref` | — | `FOUNDATION_SECRET_MASTER_KEY_REF` | 来自 `SecretStoreAdapter`；**生产缺主密钥启动失败**（ADR-026） |

### 3.6 单点登录域名与证书

| 项 | 设计 |
| --- | --- |
| 域名 | `sso.<domain>` 指向 Keycloak（issuer），`foundation.<domain>` 指向 Foundation 服务与管理台/门户；Tioka 与合同业务系统各自域名 |
| realm | `enterprise`；issuer = `https://sso.<domain>/realms/enterprise`，须与令牌 `iss` 完全一致 |
| 回调 | 各应用的 redirect URI 由 BAS-04 登记应用时自动开通 OIDC 客户端（S15 §12.1），不在 DOI 与证书运维里手工维护 |
| 证书 | TLS 在 Ingress / 网关终止；生产用客户 CA 或 cert-manager；离线环境用客户内网 CA 签发；集群内可 HTTP，**对外必须 HTTPS**（S15 §15） |
| Keycloak 主题 | 离线包带 Keycloak 主题与前端资源（约束 F43，[M4-06](M4-06-企业交付与审计.md) §0.2） |

### 3.7 会话共享（跨实例）

| 组件 | 会话处理 |
| --- | --- |
| Foundation 服务 | **无状态**：无会话亲和，多副本同时服务；缓存与锁在 Redis（键前缀 `fdn`），单实例任务（同步、对账）靠 Redis 锁互斥 |
| Keycloak | 会话与 realm 缓存用其**内置分布式缓存**（Infinispan），≥2 副本共享，无需额外中间件（S15 §16） |
| 单点注销 | Keycloak 后端通道注销通知各应用；Tioka 收到后吊销对应 `tka_` 令牌（S15 §9.3） |
| 强制下线 | 管理台调用 Keycloak 吊销会话并发 `fdn.account.sessions_revoked`，各应用据此吊销本地令牌 |
| 可用性 | Foundation 不在请求热路径：应用靠快照缓存运行，Foundation 短时不可用不影响已登录用户；Keycloak 不可用时新登录失败，已有会话在令牌有效期内可用（S15 §16） |

### 3.8 备份与恢复

- Foundation 库与 Keycloak 库**纳入 Tioka 备份方案**（[ops/HA与备份.md](../ops/HA与备份.md)）。
- 恢复要求**两库时间点一致**，恢复后再跑一次账号对账（BAS-03）补齐偏差（S15 §16）。
- 对象存储（审计归档、文件服务）纳入同一备份口径；主密钥导出经 KMS 包裹。
- 演练记录模板沿用 [ops/HA与备份.md](../ops/HA与备份.md)：RTO/RPO、最近演练日期、负责人。

```powershell
# 备份（示例，属主账号）
pg_dump -Fc -U fdn_owner -h $env:PGHOST -d foundation -f "foundation-$(Get-Date -Format yyyyMMdd).dump"
pg_dump -Fc -U keycloak -h $env:PGHOST -d keycloak  -f "keycloak-$(Get-Date -Format yyyyMMdd).dump"
```

### 3.9 升级与回滚

沿用 [M4-06](M4-06-企业交付与审计.md) §5.3 规则：

- 迁移只向前；同一里程碑内遵守「先扩后收」——新版本加列与新表，旧版本代码能在新库上运行。
- 删列、删表、收权限只在里程碑边界做，并要求先备份；升级脚本执行破坏性迁移前强制备份并提示。
- 回滚 = 应用回到上一版本镜像；跨破坏性迁移的回滚只能从备份恢复。

```powershell
# 升级（示例）
helm upgrade --install foundation deploy/helm/foundation -n foundation --create-namespace `
  -f deploy/helm/foundation/values-prod.yaml --wait
# 迁移 Job 完成后应用才启动
kubectl -n foundation wait --for=condition=complete job/foundation-migrate --timeout=10m
```

### 3.10 健康检查与就绪探针

| 探针 | 端点 | 通过条件 |
| --- | --- | --- |
| 存活 | `/actuator/health/liveness` | 进程可用 |
| 就绪 | `/actuator/health/readiness` | DB 可连、Redis 可连、Keycloak 管理接口可达、事件与快照组件就绪 |
| Keycloak | `/health/ready`（Keycloak 内置） | 数据库与缓存就绪 |
| 中间件 | compose `healthcheck` / chart `readinessProbe` | PostgreSQL `pg_isready`、Redis `PING`、RocketMQ、MinIO |

- 迁移 Job 是应用的启动前置（`initContainer` 或 `wave` 顺序）：Job 未完成前应用不就绪。
- Keycloak 启动较慢，探针 `initialDelaySeconds` 按 [deploy/compose.yml](../../../deploy/compose.yml) 的既有取值（10s / 12 次重试）对齐。

### 3.11 可观测性接入

复用 M4 OBS-11 的 OTel Collector（尾部采样）、Loki、Prometheus（[M4-06](M4-06-企业交付与审计.md) §6），Foundation 服务名 `foundation`：

| 类 | 指标 / 告警 |
| --- | --- |
| 登录 | 登录成功 / 失败、按方式与来源 IP 统计（取自 Keycloak 事件） |
| 授权 | 快照取用次数与失败、降级拒绝次数、缓存命中率、`fdn.authz.changed` 处理延迟 |
| 同步 | 同步任务成功 / 失败、暂停待确认次数、对账修复条数 |
| 事件 | `fdn-events` outbox 积压、死信数、消费幂等冲突 |
| 审计 | `audit.chain.unsealed`、`audit.chain.mismatch`（[M4-06](M4-06-企业交付与审计.md) §2.1） |
| 依赖 | Keycloak 管理接口调用成功率与超时、Redis / RocketMQ 连接 |

日志格式与 traceId 透传对齐 GOV-08；Foundation 自身管理操作全量记审计并按 GOV-09 封印（独立一条链，ADR-053 第 3 条）。

### 3.12 测试与完成标准

| 用例 | 类型 | 断言 |
| --- | --- | --- |
| `FoundationOfflineInstallSmokeIT` | 集成 | 空环境一条命令起 Foundation、Keycloak、Tioka、合同业务系统全套，冒烟通过 |
| `FoundationComposeUpIT` | 集成 | `docker compose` 叠加剖面后各单元健康、启动顺序正确 |
| `FoundationProbeIT` | 集成 | readiness 在 DB/Redis/Keycloak 不可用时为 DOWN |
| `BackupRestoreDrill`（演练记录） | 演练 | 两库时间点一致恢复；恢复后账号对账通过 |

完成标准：

- [ ] 空环境**一条命令**起全套（含 Foundation、Keycloak），离线包同一份清单（[F-00](F-00-F阶段实施计划.md) §9 第 5 条）。
- [ ] Compose 与 Helm 共用一套配置项名；Foundation 服务生产 ≥2 副本、Keycloak ≥2 副本。
- [ ] 首次启动完成 realm、内置应用、三员角色、停用应急管理员初始化。
- [ ] 迁移由独立 Job 以属主账号执行；应用账号权限符合「审计类表只有 INSERT、SELECT」。
- [ ] 备份恢复与升级回滚各演练一次，记录写 `docs/ops/`。
- [ ] `foundation.*` 配置项与 `foundation.*` 环境变量齐全、有默认值；生产缺主密钥启动失败。
- [ ] 无 `tenant_id`；未引入新中间件。

---

## 4. BAS-20 Tioka 接入 Foundation（P0，9 人天，BE-A，W84–W86，包 PF-A07a / PF-A07b）

### 4.1 目标与边界

把 Tioka 的身份、系统级权限与登录配置从自建改为由 Foundation 提供，同时**保留本地模式**。停用与强制下线、管理页跳转、FND-09/FND-10 的 Tioka 实现删除都在本项收口（[F-01](F-01-并行开发任务包.md) §4.1 交接点 3：前半先做不依赖 starter 的部分，W85 BAS-08 合入后换 `PrincipalLoader`）。

### 4.2 Tioka 侧改造清单（逐项）

| # | 改造项 | 内容 | 依据 |
| --- | --- | --- | --- |
| 1 | Principal / 登录改由 Foundation 提供 | 保留 FND-05 的「OIDC 授权码 + PKCE 换 `tka_` 令牌」（由后端完成，URL 不出现令牌）；Keycloak realm 由 Foundation 管理，Tioka 客户端由 Foundation 登记开通（BAS-04）；令牌**只带身份**（Keycloak 用户 ID、账号 ID、人员 ID、工号、姓名、主组织单元），不带角色与权限 | S15 §9.2、ADR-029 第 32 条、ADR-051 第 3、6 条 |
| 2 | 双模式开关与灰度 | `tioka.foundation.mode=local|foundation`（默认 `local`，[F-02](F-02-工程底座与骨架.md) 已定义）；按实例 / 客户灰度切换；两种模式都要通过 G1 权限闸门 | F-00 §5 约定 3、F-02 |
| 3 | 管理页跳转 Foundation | HCI-03 的**用户与系统角色**页面改为跳转 Foundation 管理台；**配额、审计查询、空间成员仍在 Tioka** | S15 §17 |
| 4 | 权限 / 数据权限改由 starter 提供 | `PrincipalLoader` 改为读**授权快照**；`@RequirePermission` 与数据范围条件由 `foundation-starter-security` 提供；**空间行级过滤（FND-03）保留**，与数据范围条件并存、互不替代 | S15 §11.3、§11.4、§17；ADR-051 第 6 条 |
| 5 | 用户、组与系统角色迁出 | 系统级角色与用户、组迁出到 Foundation；Tioka 的 `tenant_admin`（界面「系统管理员」）改为 **Tioka 应用的一个应用级角色**（权威码 `system_admin`）；**空间角色（`workspace_admin`/`builder`/`member`/`viewer`）不搬**，仍由空间成员表管理 | S15 §11.2、ADR-052 第 9 条、ADR-029 第 60 条 |
| 6 | 空间规则改引用 | ADR-026 的「组 → 空间角色」规则改为以 **Foundation 组织单元、岗位或应用角色**为条件 | S15 §17、ADR-052 第 9 条 |
| 7 | 离职事件处理 | 改由 `fdn.person.disabled` 触发：吊销 `tka_` 令牌与代用户令牌、取消进行中的 Turn、资产转交（ADR-026）、所负责数字员工暂停（TRU-03）、记忆离职清理（MEM-01）；收到 `fdn.account.sessions_revoked` 吊销本地令牌。Tioka 内的身份生命周期事件**仅本地模式保留** | S15 §10.3、§17；F-00 §5 约定 7 |
| 8 | FND-09 / FND-10 迁出 | 同步改由 BAS-11、BAS-12 承担，登录配置由 BAS-15 接管；Tioka 侧代码**删除，不留两套** | F-00 §5 约定 4 |

### 4.3 代用户令牌与 Foundation 服务的调用关系

- **FND-08 代用户令牌（`TokenVault`）仍在 Tioka，定义不变**（S15 §17）；Tioka 以**服务账号**调用 Foundation 的授权快照、字典、参数、组织查询、消息与待办接口（`foundation-starter-client`，S15 §12.2）。
- Foundation 是 Tioka 的依赖方，但**不在请求热路径**：Tioka 靠本地快照缓存运行，缓存过期且取不到快照时**拒绝而不放行**（S15 §11.3）。
- 认证与授权链路：用户 → Keycloak（令牌）→ Tioka 换 `tka_` 令牌 → starter 校验令牌 + 按「人员 + 应用」取快照 → `Principal` 的角色与权限来自快照。

### 4.4 迁移号段与回退路径

- Tioka 侧迁移 **V271（前半：用户/组/角色迁出、管理页跳转）**、**V272（后半：快照接线、FND-09/10 删除）**；承接 [F-02](F-02-工程底座与骨架.md) BAS-26 预留的 V270。
- **回退路径**：任一切换问题把 `tioka.foundation.mode` 切回 `local` 即回到自建身份与权限；Tioka 库与 Foundation 库各自独立，**回退不需要回滚数据库**（超出一个里程碑的破坏性迁移除外，见 §3.9）。

### 4.5 对 Tioka 现有 M0〜M4 的兼容性检查

| 已有设计 | 检查点 | 结论 |
| --- | --- | --- |
| FND-03 空间行级过滤（M0） | 保留 | 与数据范围条件并存，互不替代（S15 §11.4） |
| FND-04 权限点与资源 ACL（M1） | 保留；权限点目录导出为权限清单文件 | 不改格式（S15 §11.1） |
| FND-05 OIDC（M1） | 保留「换 `tka_` 令牌」；realm 与客户端改由 Foundation 管理 | 照做、不返工（S15 §17） |
| FND-06 API Key（M1） | 保留在 Tioka，所有人引用 Foundation 账号 | S15 §17 |
| FND-08 代用户令牌（M2） | 不变，仍在 Tioka | S15 §17 |
| FND-09 / FND-10（M4） | Tioka 侧删除；同步与登录只在 Foundation | F-00 §5 约定 4 |
| GOV-02 审计（M1）与 GOV-09 哈希链（M4） | 保留；BAS-10 只汇聚、不改格式，Tioka 哈希链不断 | F-00 §5 约定 5、[M4-06](M4-06-企业交付与审计.md) §2.1 |
| MEM-01 记忆离职清理（M4） | 改由 `fdn.person.disabled` 触发 | F-00 §5 约定 7 |
| M4 约束 F41〜F52 | R5 核对：FND-09 映射表结构与 BAS-11 一致、FND-10 只用 Keycloak 配置、Helm 留位、权限码已登记 | F-00 §2 R5 |

### 4.6 测试与完成标准

| 用例 | 断言 | 转绿周 |
| --- | --- | --- |
| `GfTiokaModeGateIT` | Tioka 的 **G1 权限闸门在 Foundation 模式下全部通过，本地模式也通过** | W86 |
| `TiokaModeFallbackIT` | `local` ↔ `foundation` 切换前后，关键接口权限判定一致；切回 `local` 不依赖 Foundation | W86 |
| `TiokaOffboardingIT` | `fdn.person.disabled` → `tka_` 令牌吊销、Turn 取消、资产转交、记忆清理；`fdn.account.sessions_revoked` → 本地令牌吊销 | W85–W86 |

完成标准：

- [ ] Foundation 模式下 G0〜G3 全绿（W82 起），G1 权限闸门两种模式都通过。
- [ ] `PrincipalLoader` 改读快照；系统级用户、组、角色迁出；空间角色不搬。
- [ ] 用户与系统角色页面跳转 Foundation；配额、审计查询、空间成员仍在 Tioka。
- [ ] FND-09、FND-10 的 Tioka 侧代码已删除；同步与登录只在 Foundation。
- [ ] 回退到 `local` 模式可用，且不需要回滚数据库。

---

## 5. BAS-28 合同业务系统迁到 Foundation（P0，5 人天，BE-C，W86–W87，包 PF-C08）

### 5.1 背景与范围

合同业务系统（M3-04）在 M3 按 [S15 §19.2](../systems/S15-Foundation基础平台系统.md) 的过渡约定自建了最小权限：共用 Keycloak realm、用户标识取 `sub` 与工号、角色（法务 / 经办人 / 区域负责人）用 Keycloak 客户端角色、区域权限用一张「用户 → 区域」授权表、权限码用 `资源:动作` 并维护权限清单文件（BIZ-01 +3 人天，由 M3 缓冲吸收）。BAS-28 把**用户、区域授权、角色**迁到 Foundation（[F-00](F-00-F阶段实施计划.md) §3.1）。

### 5.2 迁移映射

| 过渡方案 | 目标（Foundation） | 依据 |
| --- | --- | --- |
| 共用 realm、`sub` + 工号 | 应用登记 + OIDC 客户端（BAS-04）；令牌只带身份 | S15 §12.1、§19.2 |
| Keycloak 客户端角色（法务 / 经办人 / 区域负责人） | Foundation 中合同业务系统的**应用级角色**，由权限清单建议 | S15 §11.2 |
| 「用户 → 区域」授权表 | `region` **自定义数据维度**（值来源「应用上报」）+ 角色/人员维度授予 | S15 §11.4、ADR-052 第 10 条 |
| 权限清单文件 | 直接导入 Foundation（格式以 S15 §11.1 为准） | S15 §19.2 |
| 应用内按表过滤 | starter 数据范围条件（`foundation-starter-security`）或读取快照取值集合 | S15 §11.4 |
| 前端自建登录 | 换用 Foundation 前端包（登录、菜单路由、按钮指令、选择器） | S15 §12.3、ADR-054 第 4 条 |

### 5.3 迁移步骤与产物

1. 在 Foundation 登记合同业务系统、导入权限清单（菜单、权限码、`region` 维度声明）。
2. 把「用户 → 区域」表逐条导入为 `region` 维度取值与授予（人员维度授予 `source=MANUAL`）。
3. 角色由 Keycloak 客户端角色改为 Foundation 应用级角色；重算授权快照。
4. 业务系统换用 starter 与前端包；按区域查询改由 starter 生成条件。
5. 迁移演练与比对：**同一用户迁移前后可见合同集合一致**；比对由 BE-C 出脚本，业务系统负责人评审（[F-00](F-00-F阶段实施计划.md) §4 交接点 4）。
6. 过渡期「用户 → 区域」表**停用**（[F-00](F-00-F阶段实施计划.md) §9 第 3 条）；对应用户的 Keycloak 客户端角色下线。

迁移不新增 Tioka 迁移号；Foundation 侧复用 BAS-05/06 的表结构；业务系统侧删除本地角色表与授权表（其自身库的清理不在两侧 Flyway 号段内）。

### 5.4 测试与完成标准

| 用例 | 断言 | 转绿周 |
| --- | --- | --- |
| `GfBizMigrationGateIT` | 迁移前后同一用户可见合同集合一致；过渡期表停用后行为不变 | W87 |
| `BizRegionScopeIT` | 区域负责人的查询只返回其区域集合内的合同 | W87 |

完成标准：

- [ ] 迁移前后同一用户可见合同集合一致。
- [ ] `region` 维度取值与 Tioka 侧口径一致（ADR-052 第 11 条）。
- [ ] 业务系统不再自建角色与「用户 → 区域」表；权限清单随版本导入。
- [ ] `GfBizMigrationGateIT` 转绿。

---

## 6. BAS-22 GF 端到端闸门与闸门基座收口（P0，3 人天，BE-B，W87，包 PF-B09）

### 6.1 闸门用例清单（含前置数据与断言）

以 [F-00](F-00-F阶段实施计划.md) §6.1 为准（12 条）；由对应包去掉 `@Disabled` 转绿（[F-01](F-01-并行开发任务包.md) §5）。用例均为 `gate/Gf*GateIT`、`@Tag("gate")`，跑在 Foundation 闸门基座（Testcontainers：PostgreSQL、Redis、RocketMQ、Keycloak 单例，[F-01](F-01-并行开发任务包.md) / [F-02](F-02-工程底座与骨架.md)）。

| 用例 | 前置数据 | 断言 | 负责 | 转绿周 |
| --- | --- | --- | --- | --- |
| `GfSmokeIT` | Testcontainers 起 Foundation 服务、Keycloak、PostgreSQL；realm `enterprise` 已建 | 三容器健康；Foundation 管理接口可连；readiness 为 UP | BE-C | W79 |
| `GfOrgImportGateIT` | 500 人组织 Excel（含兼职、数字员工类型） | 导入后组织树、岗位、兼职任职、数字员工任职正确；重复导入幂等 | BE-B | W81 |
| `GfAccountLifecycleGateIT` | 已导入人员与启用账号 | 停用后 Keycloak 登录失败；人为改 Keycloak 后每日对账发现并修复 | BE-B | W82 |
| `GfSsoGateIT` | 已登记新应用（地址与回调） | 自动开通 OIDC 客户端并完成单点登录；密钥轮换后旧密钥失效 | BE-C | W82 |
| `GfPermissionGateIT` | 权限清单、角色与授权（人员 / 组织单元 / 岗位） | 清单导入幂等；岗位授权对新入职者生效；互斥角色不能同时授予；分级管理员不能越权；三员分立成立 | BE-C | W83 |
| `GfDataScopeGateIT` | 华东区负责人角色；部门五档各一例 | 华东区负责人只拿到华东区取值；五档各一例取值正确 | BE-C | W83 |
| `GfSyncGateIT` | 飞书测试租户、LDAP/AD 测试目录；同步来源与字段映射 | 飞书部门与人员同步正确；超阈值停用暂停待确认；删除保护生效 | BE-B | W83 |
| `GfSnapshotEventGateIT` | 角色与授权变更；示例应用 starter 缓存 | 授权变更后 5 秒内 `fdn.authz.changed` 发出、快照版本递增、starter 缓存失效 | BE-C | W84 |
| `GfOffboardingGateIT` | 已登录 Tioka 与合同业务系统的在职人员 | 停用后两边会话 1 分钟内失效；交接待办生成；记忆清理触发 | BE-B | W85 |
| `GfTiokaModeGateIT` | Tioka 可在 `local` 与 `foundation` 两模式启动 | G1 权限闸门在 Foundation 模式全部通过；本地模式也通过 | BE-A | W86 |
| `GfBizMigrationGateIT` | 过渡方案的「用户 → 区域」表与客户端角色；已迁移授权 | 迁移前后同一用户可见合同集合一致 | BE-C | W87 |
| `GfFoundationE2EIT` | 入职人员、区域授权、Tioka 与合同业务系统已接入、门户与前端包 | 入职 → 登录 Tioka 与合同业务系统 → 按区域看到数据 → 离职后两边会话失效 | BE-B | W87 |

> **命名以 [F-00](F-00-F阶段实施计划.md) §6.1 为准**。BAS-08 的「六项接入自检」（登录、菜单、按钮、数据范围、单点注销、离职联动，S15 §12.1）在示例应用上以 `*IT` 体现，不另立 GF 独立用例名；BAS-10 审计与 BAS-16 消息中心的验收在其各自 `*IT` 与 `GfFoundationE2EIT` 中覆盖。

### 6.2 端到端用例

`GfFoundationE2EIT`（对应 F-00 §1 第 5 条与 §9 第 1 条）：

1. 入职：导入人员并开通账号 → 按「组织或岗位 → 角色」规则授予角色，推送账号到 Keycloak。
2. 登录：登录 Tioka 与合同业务系统（单点登录，一次认证）。
3. 数据：按 `region` 数据范围看到各自可见的数据集。
4. 离职：账号停用 → Keycloak 会话吊销 → 发 `fdn.person.disabled` 与 `fdn.account.sessions_revoked` → 两边会话 1 分钟内失效、交接待办生成、记忆清理触发。

`GfFoundationE2EIT` 要求**连续 3 天绿**（W88）。

### 6.3 闸门基座收口

- 基座：Testcontainers 单例（PostgreSQL、Redis、RocketMQ、Keycloak），复用 [F-02](F-02-工程底座与骨架.md) BAS-01 建立的闸门基座；GF 12 条用例在 CI 独立任务中运行（`-Pgate` 只跑本包）。
- 初始化：基座启动时导入 realm `enterprise`、内置应用与三员角色（BAS-21 的初始化脚本复用）。
- 收口内容：把各包散落的 `@Disabled` 按转绿周次去掉；补齐跨用例共享的测试数据构造（组织、人员、区域、应用）；确保用例相互隔离（每条用例自建数据，不依赖执行顺序，[00-施工总纲](00-施工总纲.md) §2.6）。

### 6.4 回归

W88–W89（[F-00](F-00-F阶段实施计划.md) §6.2）：

- Foundation 模式下重跑 **G0〜G4** 全部闸门。
- 本地模式下重跑 **G1、G4**。
- 离线安装演练**包含 Foundation 与 Keycloak**，从空环境一条命令起全套。

### 6.5 完成标准

- [ ] F-00 §6.1 的 12 条用例全部绿；`GfFoundationE2EIT` 连续 3 天绿。
- [ ] Foundation 模式下 G0〜G4 全部回归通过；本地模式下 G1、G4 通过。
- [ ] 用例相互隔离、可重复；CI 全绿。

---

## 7. 验收清单

### 7.1 功能

- [ ] 12 条 GF 用例全绿，`GfFoundationE2EIT` 连续 3 天绿（[F-00](F-00-F阶段实施计划.md) §9）。
- [ ] Tioka 两种模式 G1 通过，Foundation 模式 G0〜G4 回归通过。
- [ ] 合同业务系统迁入，迁移前后可见合同集合一致，过渡表停用。
- [ ] FND-09、FND-10 的 Tioka 侧代码删除。
- [ ] 空环境一条命令起全套；工程模板从零起新子系统 1 天内通过接入自检。

### 7.2 性能

| 项 | 判据 | 依据 |
| --- | --- | --- |
| Tioka 关键接口 P95 | Foundation 模式相对 `local` 模式上升 **≤20%** | [F-00](F-00-F阶段实施计划.md) §7 风险 6 |
| 离职会话失效 | **≤60 秒**（要求「1 分钟内」） | [F-00](F-00-F阶段实施计划.md) §6.1 |
| 授权变更事件 | 变更后 **≤5 秒**发出 | S15 §11.3 |
| 快照缓存 | 命中时请求不再调用 Foundation | S15 §11.3 |

### 7.3 安全

- [ ] 等保三级常见默认值：密码策略、失败锁定、会话超时、三员互斥（可部署时关闭）、敏感字段加密与脱敏（S15 §15）。
- [ ] **禁止 `tenant_id` / `sys_tenant`**；无租户拦截器（ADR-029 第 60 条）。
- [ ] 传输全部 HTTPS；服务间调用用**服务账号令牌**，不用固定密钥。
- [ ] 生产缺主密钥启动失败；应急管理员默认停用、启用需双人确认并审计。
- [ ] 审计与登录日志在线保留 ≥180 天，SEC 类按 [ADR-072](../adr/ADR-072-audit-event-model-coverage.md) 在线 400 天、归档 3 年；审计链校验通过。

### 7.4 可观测

- [ ] 接入 OTel Collector、Loki、Prometheus（复用 M4 OBS-11）。
- [ ] `fdn-events` outbox 积压与死信、`audit.chain.*`、Keycloak 登录失败、快照降级拒绝均有指标与告警。
- [ ] 健康与就绪探针就位；迁移 Job 为启动前置。

### 7.5 备份恢复演练

- [ ] Foundation 库与 Keycloak 库纳入备份，**时间点一致**恢复，恢复后跑一次账号对账。
- [ ] 升级与回滚各演练一次；记录写 `docs/ops/`（RTO/RPO、最近演练日期、负责人）。

---

## 8. 上线检查单、灰度与回滚判据

### 8.1 上线检查单

- [ ] R1〜R8 全部满足（[F-00](F-00-F阶段实施计划.md) §2）；R5 的 M4 约束核对通过。
- [ ] 全功能冻结（W87），GF 12 条用例绿、E2E 连续 3 天绿。
- [ ] Foundation 服务 ≥2 副本、Keycloak ≥2 副本；探针与启动顺序正确。
- [ ] realm、内置应用、三员角色、停用应急管理员已初始化；主密钥已注入。
- [ ] 离线清单含 Foundation 与 Keycloak 镜像并有摘要；离线包一份清单。
- [ ] 备份与恢复演练、升级与回滚演练已完成并记录。
- [ ] `tioka.foundation.mode` 默认值、灰度范围与回滚开关已确认。

### 8.2 灰度

| 步 | 动作 | 观察 |
| --- | --- | --- |
| 1 | 非生产 / 影子客户把 `tioka.foundation.mode` 切 `foundation` | 登录成功率、权限校验失败率、P95、审计链 |
| 2 | 逐批扩到生产客户 | 同上 + 快照取用失败率、离职会话失效时延 |
| 3 | 全量后保留 `local` 快速回退开关 | 持续观察一个观察期 |

### 8.3 回滚判据

出现任一情况即把 `tioka.foundation.mode` 切回 `local`（**不回滚数据库**；Tioka 库与 Foundation 库各自独立）：

- Foundation 模式下登录成功率低于约定阈值，或权限误判（越权 / 误拒）出现。
- Tioka 关键接口 P95 相对 `local` 模式上升超过 20% 且缓存优化无效（[F-00](F-00-F阶段实施计划.md) §7 风险 6、6.1 节）。
- 快照连续取用失败触发「降级拒绝」，影响已登录用户。
- 审计链断裂（`audit.chain.mismatch`）或 `fdn-events` 持续积压。

跨破坏性迁移的回滚只能从备份恢复；升级脚本在执行破坏性迁移前强制备份并提示（§3.9）。

---

## 9. 完成标准

1. [F-00](F-00-F阶段实施计划.md) §9 的 6 条全部满足。
2. [F-00](F-00-F阶段实施计划.md) §6.1 的 12 条用例全部绿，`GfFoundationE2EIT` 连续 3 天绿。
3. 第 7 节验收清单（功能、性能、安全、可观测、备份恢复演练）逐条满足。
4. 硬约束守住：无 `tenant_id`；两仓库迁移号段互不干扰；未引入未经 ADR 许可的新中间件。
5. 本文涉及的 `*IT` / `*Test` / 演练记录齐全，CI 全绿。

---

## 与其他文档的关系

| 文档 | 关系 |
| --- | --- |
| [S15 Foundation 基础平台系统](../systems/S15-Foundation基础平台系统.md) | 设计权威：§9（认证与会话）、§11（权限中心与快照）、§12.1（接入步骤）、§13（公共服务）、§16（部署与运维）、§17（对现有设计的影响）、§19.2（过渡约定） |
| [F-00 F 阶段实施计划](F-00-F阶段实施计划.md) | 排期与人天权威：BAS-21/20/28/22 行、§2 前置 R1〜R8、§4 交接点 3/4/5、§5 实现约定、§6.1 GF 用例、§9 完成标准 |
| [F-01 并行开发任务包](F-01-并行开发任务包.md) | 包与仓库边界：PF-B06（BAS-21）、PF-A07a/b（BAS-20）、PF-C08（BAS-28）、PF-B09（BAS-22）；§1 双仓库、§5 合入顺序、§6 硬约束 |
| [F-02 工程底座与骨架](F-02-工程底座与骨架.md) | 上游：BAS-26 定义 `tioka.foundation.mode` 与 V270 号段；BAS-01 建立闸门基座；提供 `foundation.*` 配置前缀 |
| [F-04 账号与登录与离职联动](F-04-账号与登录与离职联动.md) | 上游：BAS-14 发 `fdn.person.disabled` / `fdn.account.sessions_revoked`；`GfAccountLifecycleGateIT`、`GfOffboardingGateIT` |
| [F-05 应用与权限中心](F-05-应用与权限中心.md)、[F-06 数据权限与授权快照](F-06-数据权限与授权快照.md) | 上游：权限清单、角色与授权、`region` 维度、授权快照与 `fdn.authz.changed`；BAS-08 starter 提供权限注解与数据范围条件 |
| [M4-06 企业交付与审计](M4-06-企业交付与审计.md) | 前置与对齐：§0.1 数据库账号分离、§0.2 离线清单、§5 FND-11 Helm 与空气隔离包（BAS-21 在其上补 Foundation 部分） |
| [M1-01 权限与身份联邦](M1-01-权限与身份联邦.md) | 体例参考；FND-04/05/06 保留，`Principal` 在 BAS-20 起改读快照 |
| [00-施工总纲](00-施工总纲.md) | 全局约定：§2 包与分层、标识与时间、事务与并发、配置、测试分层 |
| [ops/HA与备份.md](../ops/HA与备份.md) | 备份范围与演练记录模板（Foundation 库与 Keycloak 库纳入同一方案） |
| [ADR-051](../adr/ADR-051-foundation-platform.md)〜[ADR-054](../adr/ADR-054-engineering-base-subsystem-microfrontend.md)、[ADR-026](../adr/ADR-026-foundation.md) | 定位与接入、组织与账号、公共服务与部署、底座与微前端；外部身份映射与离职处理 |
| [ADR-029](../adr/ADR-029-design-rulings.md) | 第 60 条（禁止 `tenant_id`）、第 69 条（Foundation 定案）、第 76 条（统一排期 W80–W89）、第 80 条（F-00/F-01 与双仓库号段）、第 81 条（F2 增强批次不在本文） |
