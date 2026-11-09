# M2 升级顺序（迁移 → 基础设施 → 应用 → 消费者）

> 状态：2026-11-10（M2 残余收口批次 B-3）。
> 事实来源：`tioka-server/src/main/resources/db/migration/`（46 个文件）、`application.yml` flyway 段、
> `deploy/compose.yml`、`RocketMqConsumerBootstrap`、`docs/contracts/README.md`、M2-00 第 11 节第 4 条。

## 1. Flyway 迁移段概览（V101〜V221 已落号清单）

`tioka.flyway.out-of-order: true`（ADR-029 第 33 条修订：迁移随工作项合入，合入顺序可能与版本号不同），
`locations: classpath:db/migration`，`baseline-on-migrate: false`。当前 main 上的落号：

| 段 | 版本 | 内容 |
| --- | --- | --- |
| M0 尾段 | V101〜V108 | runtime kernel / model quota audit / auth seq seed / drop legacy 等（V101、V102、V103、V105、V106、V107、V108） |
| M1 | V110〜V129 | authz、visibility、audit forward、identity federation、runtime recovery approval、chat index、model gateway、embedding dim、agent spec；V127 audit request_id；**V128** rename session→thread；**V129** rename flow_tool_pub→flow_publication |
| M2 主体 | V130〜V144 | **V130** outbox；V131 rule pause trace；**V132** orchestration；V133 correlation context；V136 rename tenant_admin→system_admin；V137 rule version F17；**V140** im_feishu；V143 approval desk trajectory flow；V144 trust change set |
| M3 契约先行 | V150〜V216 | V150 cfg version framework；V171 schema registry；V172 ontology；V201 trust charter；V210 plugin registry；V216 connector instance |
| P2/P3/收口 | V217〜V221 | V217 audit span service；V218 model supply budget；V219 audit chain seal；V220 ext interaction ledger；**V221** obs05 audit catalog mutex |

另有 4 份 **Java 迁移**在 `tioka-server/src/main/java/db/migration/`：
V104（BackfillRuntimeItems）、V124（BackfillAgentSpec）、V126（DropChatMessage）、
**V145（CredentialReencrypt，ECB→GCM）**——与 SQL 同级参与 Flyway 版本序。

注意：M2-00 §11 第 4 条提及的 **V141（UserTokenVault）未落号**（34 号复盘清单列 M2 残余）。

## 2. 升级总顺序

1. **备份**：PostgreSQL 全量备份（含 `secret_store`、`sys_outbox`、`sys_dead_letter`）。
2. **主密钥注入**（V145 存量库升级必需）：`TIOKA_SECRET_MASTER_KEY`（旧 ECB）与
   `TIOKA_CREDENTIAL_MASTER_KEY`（新 GCM）同时注入，见 `m2-master-secret.md` 第 4 节。
3. **基础设施**（`docker compose -f deploy/compose.yml up -d`）：
   - postgres（pgvector/pg16，init-pgvector.sql 初始化）；
   - redis；keycloak（如启用 OIDC）；jaeger（GOV-08）；
   - rocketmq-namesrv → rocketmq-broker（`depends_on` 保证 NameServer 先起，Broker 的
     `NAMESRV_ADDR=rocketmq-namesrv:9876`）。compose 内各服务相互独立，仅 broker→namesrv 一条依赖边。
4. **应用启动**（compose 无 tioka-server 服务，按部署形态自行拉起）：
   - Flyway 随应用启动自动迁移（out-of-order 允许补插历史版本号）；
   - prod profile（application-prod.yml）：messaging.mode=rocketmq、relay-enabled=true、
     model/mcp 关 mock、secret.require-external-key=true。
5. **RocketMQ 消费者就绪**：`RocketMqConsumerBootstrap` 监听 `ApplicationReadyEvent`——
   即**全部 Bean 初始化完成、应用对外就绪之后**才逐个 `DefaultMQPushConsumer.start()`。
   需设置 `rocketmq.name-server`（如 `TIOKA_MQ_NAMESRV=localhost:9876`），否则只 WARN 不启动。

## 3. MQ 消费者就绪时序注意

- 启动后按日志 `RocketMQ 消费者已启动 group=... topic=... tag=...` 确认每个消费者在线；
  未配置 name-server 的实例**不参与消费**（静默跳过），多实例滚动升级时注意半数实例空转的窗口。
- 消费为 `MessageListenerOrderly` 顺序消费 + `sys_consumed` 幂等：升级窗口内消息只会延迟，不会丢或重放副作用。
- outbox 中继（relay-enabled=true）靠 Redis 锁（lock-ttl 15s）单发：旧实例下线后锁 15s 内由新实例接管。
- 升级期间 Broker 不可用：生产者发送失败走 outbox（事务内先落 outbox，中继重试），恢复后自动补发。

## 4. 回滚注意（只回代码，不回迁移）

- **Flyway 迁移一律不回滚**：已执行的版本号留在 `flyway_schema_history`，回滚到旧版应用时
  旧代码面对多出的表/列是兼容的（历史演进契约见 `docs/contracts/README.md`）。
  禁止手工删行/删表回退迁移——out-of-order=true 下会破坏版本链。
- V145（重加密）回滚注意：迁移本身整体事务、失败即回滚；**但一旦成功，旧 ECB 密文已被 GCM 覆盖**，
  回滚旧版应用必须保留 `TIOKA_SECRET_MASTER_KEY` 与 `TIOKA_CREDENTIAL_MASTER_KEY` 两条环境变量，
  否则旧代码读 GCM 密文失败。
- 回滚步骤：停新版本实例 → （保留数据库现状）→ 用上一版本镜像/制品重启 → 确认
  Flyway 不执行新迁移（validate 通过）→ 确认消费者日志齐全。
- 升级失败中断处理：Flyway 失败时应用不启动；排查后以修复版本继续（可 out-of-order 补插），
  不人工改 `flyway_schema_history`。

## 5. 升级检查清单

- [ ] 数据库备份完成。
- [ ] 新旧主密钥注入（涉及 V145 的存量环境）。
- [ ] compose 基础设施健康（pg_isready / redis-cli ping / broker 注册到 NameServer）。
- [ ] 应用启动日志：Flyway 全部成功、无开发密钥 WARN、消费者启动日志齐全。
- [ ] 死信表 `sys_dead_letter` 无新增 OPEN 行。
- [ ] 回滚预案：上一版本制品可用 + 密钥环境变量保留 + 不回迁移的共识已同步。
