# ADR-051 — Foundation 基础平台：定位、架构与应用接入

- 状态：Accepted（设计定案，待实现；2026-11-06 负责人选定排期方案 C：在 M4 之后的 F 阶段实施）
- 日期：2026-11-06
- 覆盖工作项：BAS-01、BAS-04、BAS-08、BAS-20〜22、BAS-FE-01、BAS-FE-02、BAS-FE-08
- 详细设计：[S15 Foundation 基础平台系统](../systems/S15-Foundation基础平台系统.md)
- 相关：[ADR-015](ADR-015-identity-tenant-rbac.md)、[ADR-026](ADR-026-foundation.md)、[ADR-029](ADR-029-design-rulings.md) 第 57、60、69 条、[M3-04](../construction/M3-04-租赁合同风险审查.md)
- 约束：与 ADR-029 定案冲突时以定案为准；修改本 ADR 走 [ADR-030](ADR-030-anti-drift-process.md) 的变更流程。

## 背景

身份、组织、权限目前都在 Tioka 的 `system` 模块里，只服务 Tioka。合同场景要新建独立部署的业务系统（M3-04），它同样需要用户、组织、角色、菜单、按区域的数据权限、字典和日志；客户后续还会有更多业务系统。负责人要求设计一个 Foundation 系统，对标若依的系统管理能力与 IAM/SCIM。

## 决定

0. **Foundation = Java 工程化底座 + 通用企业中台**。底座、子系统授权模式与微前端见 [ADR-054](ADR-054-engineering-base-subsystem-microfrontend.md)；本 ADR 定中台服务的定位与接入。
1. **Foundation 中台是独立部署的系统**：自己的服务、库、管理台与门户；Tioka、业务系统、Foundation 管理台都是它的「子系统」（「接入应用」与「子系统」同义）。技术栈与 Tioka 一致。代码放单独仓库 `tioka-foundation`。
2. **Keycloak 负责登录协议，Foundation 负责管理与主数据**。OIDC、SAML、LDAP 联邦、IM 扫码、TOTP 都用 Keycloak 配置实现；Foundation 管人员、组织、账号、应用、角色、菜单、数据范围，经 Keycloak 管理接口推送账号、开通客户端、吊销会话。所有应用共用一个 realm（延续第 57 条）。
3. **令牌只带身份**（Keycloak 用户 ID、Foundation 账号与人员 ID、工号、姓名、主组织单元），不带角色与权限。应用按「人员 + 应用」取授权快照并缓存，收到 `fdn.authz.changed` 后失效；Foundation 不可达且缓存过期时拒绝而不是放行。
4. **应用接入靠 SDK 与契约**：后端 Spring Boot starter（令牌校验、快照缓存、权限注解、数据范围条件、审计上报、字典参数、组织查询），前端 Vue 包（登录、菜单路由、按钮指令、字典组件、组织人员选择器）。非 Java 应用照 OpenAPI 与事件契约接入。接入以六项自检为完成标准：登录、菜单、按钮、数据范围、单点注销、离职联动。
5. **Foundation 不在请求热路径上**：应用靠快照缓存运行；Foundation 短时不可用不影响已登录用户。
6. **Tioka 的接入方式**：保留 FND-05 的「OIDC 换 `tka_` 令牌」；`PrincipalLoader` 改为从授权快照取系统级角色与权限；空间成员、资源 ACL、API Key、代用户令牌仍在 Tioka。
7. 不引入租户（第 60 条）；集团多公司用组织树表达。

## 备选

| 备选 | 不选原因 |
| --- | --- |
| 留在 Tioka `system` 模块，业务系统各自实现 | 重复建设；离职、权限口径不能统一 |
| 引入若依 Vue-Plus 作基座 | 多租户、Sa-Token、定时任务、代码生成等需大量裁剪，与第 60 条和 Tioka 的 Spring Security 体系冲突 |
| Spring Authorization Server 自建认证 | SAML、LDAP、IM 代理、MFA 要自己做，多约 30 人天 |
| 权限放进令牌 | 令牌膨胀，权限变更要等令牌过期 |
| 放在 Tioka 仓库的独立 Maven 工程 | 版本节奏与 Tioka 绑定，单独交付 Foundation 不便；负责人如更看重共用 CI 可改选 |

## 后果

- FND-09、FND-10、FND-11 在 M4 照做；F 阶段由 BAS 工作项接管并迁入 Foundation；HCI-03 的用户与系统角色页面届时改为跳转 Foundation。
- 业务系统在 M3 按 S15 第 19.2 节过渡约定自建最小权限（BIZ-01 +3 人天，M3 缓冲吸收），F 阶段经 BAS-28 迁移。
- 部署多两个单元（Foundation、Keycloak 原本已有），中间件共用。
- 排期见 S15 第 19 节：方案 C，F 阶段约 W70–W80（以 M4 结束于 W69 计），M1〜M4 周次不变。
