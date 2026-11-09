# M2 主密钥管理（SecretStore / CredentialCipher）

> 状态：2026-11-10（M2 残余收口批次 B-3）。
> 事实来源：`AesGcmCredentialCipher` / `CredentialSecurityAutoConfiguration` / `LegacyAesEcb`（tioka-framework security 包）、
> `LocalSecretStore`（system-biz）、`V145__CredentialReencrypt.java`、
> `docs/architecture/ops/私有化安装手册.md` 第 8〜9 条、M2-00 开工前置 Q6。

## 1. 两把主密钥（口径必须分清）

| 密钥 | 配置键 | 环境变量 | 用途 |
| --- | --- | --- | --- |
| 遗留 ECB 主密钥 | `tioka.secret.master-key` | `TIOKA_SECRET_MASTER_KEY` | 解密 V145 之前的 AES/ECB 旧密文（读路径兼容） |
| GCM 主密钥（FND-08 / ADR-042） | `tioka.credential.master-key` | `TIOKA_CREDENTIAL_MASTER_KEY` | 所有**新写入**的凭证密文（AES-256-GCM） |
| 密钥版本 | `tioka.credential.key-version` | `TIOKA_CREDENTIAL_KEY_VERSION` | 默认 1；密文前缀 `v{n}:` 的 n |
| 外部密钥强制 | `tioka.secret.require-external-key` / `tioka.credential.require-external-key` | — | prod profile 下**缺 key 直接拒绝启动**（LocalSecretStore / AesGcmCredentialCipher 构造器抛 IllegalStateException） |

- 非 prod 且未配置时：使用内置开发密钥（`LegacyAesEcb.DEV_DEFAULT_MASTER_KEY` /
  `AesGcmCredentialCipher.DEV_DEFAULT_MASTER_KEY = "tioka-dev-credential-master-v1"`）并 WARN。
- 生产生成口径（私有化安装手册）：`openssl rand -base64 32`，存入密钥库 / 环境注入，仅运维可轮换。

## 2. 密文格式与算法（AesGcmCredentialCipher）

- 密文：`v{keyVersion}:base64(iv[12] ‖ ciphertext ‖ tag[16])`，AES-256-GCM，tag 128 bit。
- AAD = `UTF-8(credentialId | ownerId)`——与存储键绑定，换 key / 换列 / 捪号需同 AAD 才能解。
- 主密钥派生：`SHA-256(masterKey)` → 32 字节 AES key（无 KDF 迭代，密钥强度即主密钥强度）。
- **解密只认当前 keyVersion**（`未知密钥版本` 异常）——多版本共存轮换**未落地**，源码注释
  「本阶段仅支持当前版本；轮换多密钥在后续登记」。
- 装配：`CredentialSecurityAutoConfiguration` 提供 `CredentialCipher` Bean 与
  `CredentialReencryptRegistry`（登记 `secret_store.cipher_text` 一列）。

## 3. SecretStore 读写双轨（LocalSecretStore）

- 写（`put`）：一律走 `CredentialCipher.encrypt`（GCM）。
- 读（`get`）：`LegacyAesEcb.looksLikeLegacy(ct)` 命中 → 用遗留 ECB 主密钥解；否则按 GCM 解。
- 消费方：model-biz 供应商凭证（`ProviderClient` / `ModelAdminService`）、connector `ConnectorSecretBridge`、
  flow `FlowSecretBridge`、mcp 等经 system-api 的 `SecretStore` SPI 取值，不直接接触密钥。

## 4. V145 重加密迁移（现状登记）

- 迁移实现：`tioka-server/src/main/java/db/migration/V145__CredentialReencrypt.java`（Java 迁移，
  与 V104/V124/V126 同属 src/main/java 下的 4 份 Java 迁移；`docs/contracts/README.md` 第 39 行口径）。
- 行为：读 `secret_store` 全部未删行 → ECB 行解密 → GCM 重加密写入暂存列 `cipher_text_gcm` →
  逐行解密回验 + 条数校验 + 总数校验 → 回写 `cipher_text` → 暂存列删除；任一步失败整体失败（Flyway 事务内）。
- 主密钥取值顺序：遗留 ECB 用 `TIOKA_SECRET_MASTER_KEY`（env）→ system property → 开发缺省；
  GCM 用 `TIOKA_CREDENTIAL_MASTER_KEY` → `tioka.credential.master-key` → 开发缺省；
  版本 `TIOKA_CREDENTIAL_KEY_VERSION`（缺省 1）。**V145 执行前必须同时注入新旧两把密钥**。
- 验收 IT：`tioka-server/src/test/java/com/tioka/server/security/CredentialReencryptIT.java` 已在 main。
- **未落地 / 待收口（34 号复盘清单第 49 行口径）**：FND-08 整项仍列 M2 残余——
  `UserTokenVault`（V141，代用户令牌保管）未落地；V145 目前只覆盖 `secret_store.cipher_text`，
  PK-14a 设想的「供应商 API Key 等其他表列」的重加密登记未扩展；轮换多密钥版本共存未实现。
  复盘口径与 main 现状（V145 文件已存在）的差异需负责人确认后关闭该项。

## 5. 轮换注意事项（当前能力边界）

1. **当前不支持在线轮换**：decrypt 只认 `tioka.credential.key-version` 一个版本；
   提升版本号后旧 `v1:` 密文全部不可解。轮换的正确顺序（待实现后）：注入 v1/v2 双密钥 →
   登记「表+列」重加密任务 → 重加密扫尾 → 撤 v1。落地前轮换 = 按 V145 同款流程做一次性迁移。
2. 轮换前备份 `secret_store` 全表；V145 式迁移失败会整体回滚，但备份是最后防线。
3. 换 key 必须保证 AAD 口径（credentialId|ownerId）不变，否则 GCM 校验失败按密文损坏处理。
4. `TIOKA_SECRET_MASTER_KEY`（ECB）在全部存量密文确认已是 `v{n}:` 格式后可下线；
   下线前保留只读，供读路径兜底。
5. prod profile 缺任一必填密钥直接启动失败——这是预期行为，不要通过设置 `require-external-key=false` 绕过。

## 6. 运维检查点

1. 启动日志无 `使用内置开发主密钥` WARN（出现即说明生产密钥未注入）。
2. `secret_store.cipher_text` 抽样确认前缀 `v1:`（ECB 遗留格式应已清零，V145 后）。
3. 密钥注入方式审计：确认密钥不出现在进程命令行参数 / 镜像层 / 日志中。
