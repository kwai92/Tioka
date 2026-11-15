# ADR-076 — 应用间授权访问（M2M 与用户委派）

- 状态：Accepted（设计定案，待实现；放入 F 阶段之后的 **F2 增强批次**，见 ADR-029 第 81 条）
- 日期：2026-11-13
- 覆盖工作项：BAS-29、BAS-30、BAS-FE-15
- 详细设计：[S15](../systems/S15-Foundation基础平台系统.md) 第 22 节
- 相关：[ADR-051](ADR-051-foundation-platform.md)、[ADR-052](ADR-052-org-account-authz-center.md)、[ADR-054](ADR-054-engineering-base-subsystem-microfrontend.md)、[ADR-039](ADR-039-delegation-grant.md)、[ADR-045](ADR-045-a2a-interop.md)、[ADR-074](ADR-074-external-interaction-ledger.md)
- 约束：与 ADR-029 定案冲突时以定案为准；修改本 ADR 走 [ADR-030](ADR-030-anti-drift-process.md) 的变更流程。

## 背景

Foundation 到 ADR-051〜054 为止，管的是「人 → 应用」的授权：用户在某个子应用里有什么角色、菜单、权限码。负责人 2026-11-13 明确 Foundation 还要「管理业务应用并提供业务应用间的授权访问」——即**应用 → 应用**的授权：应用 A 要调应用 B 的接口，既包括没有人参与的定时任务与后台流程（机器对机器），也包括「应用 A 代表当前登录用户去访问应用 B」（用户委派）。

现状里这件事没有位置：ADR-021 的连接器解决的是「Agent 工具 → 外部系统」，ADR-039 的委派授予解决的是「父 Agent → 子 Agent」的权限收窄，ADR-045 的 A2A 解决的是「本平台 Agent ↔ 外部组织 Agent」的互操作，都不覆盖「同一企业内两个业务应用之间的受控调用」。

## 决定

1. **两种模式，都用标准协议**：机器对机器（M2M）用 OAuth 2.0 `client_credentials`；用户委派（OBO，on-behalf-of）用 OAuth 2.0 Token Exchange（RFC 8693）。两者都由 Keycloak 承担协议与令牌签发，Foundation 管授权关系与登记，不重写认证协议（延续 ADR-051 第 2 条）。

2. **应用间授权关系是 Foundation 的主数据**。新增实体「应用授权」（`fdn_app_grant`）：调用方应用、被调应用、允许的能力集合（权限码，格式沿用 `资源:动作`）、模式（M2M / OBO / 两者）、生效与失效时间、状态（待审批 / 生效 / 已撤销）、来源与审批记录。**默认拒绝**：没有登记的调用方对，被调应用一律拒绝。

3. **M2M**：调用方应用使用自己的服务账号取令牌，令牌 `audience` = 被调应用、`act` = 调用方应用标识、`sub` = 服务账号。令牌只带身份与调用方标识，**不带权限**（延续 ADR-051 第 3 条）；被调应用按「调用方应用 + 被调应用」取授权关系与快照判定。

4. **OBO 用户委派**：应用 A 在用户已登录的前提下，用 Token Exchange 换出「以用户为主（`sub` = 用户）、以 A 为代理（`act` = 应用 A）」的令牌。被调应用 B 执行时的权限 = **用户权限 ∩ 调用方被授予能力**（收窄）：
   - 用户权限取自该用户在 B 应用的授权快照；
   - 调用方能力取自 `fdn_app_grant` 中 A → B 的授予；
   - 两者取交集，避免「应用把自己没有的能力借助高权限用户放大」。收窄语义与 ADR-039 委派授予一致。

5. **执行在应用内，Foundation 不在热路径**（延续 ADR-051 第 5 条）：
   - 被调应用经 starter 校验：撤销（`aud` 是否为本应用）、调用方是否登记、授权关系是否生效、OBO 时叠加用户权限收窄；
   - 调用方经 SDK（`foundation-starter-client` 扩展）自动取/缓存令牌、透传 `request_id` 与 `traceparent`（ADR-073）、按 ADR-074 写 `ext_interaction` 台账；
   - 被调应用提供方法级注解 `@RequireAppGrant(appCode, permission)`，不满足返回 403 并上报审计。

6. **授权关系变更即时生效**：Foundation 变更后发事件 `fdn.app.grant.changed`（含调用方、被调方、新版本号）；被调应用 starter 按 `(调用方应用, 被调应用)` 失效缓存。事件丢失时依靠缓存 TTL 兜底（与 ADR-051 第 3 条同机制）。

7. **审计与台账双向记录**：调用方与被调应用各记一条（调用方记「我调了谁、代表谁」，被调应用记「谁调了我、代表谁、结果如何」）。OBO 调用必须在台账里区分「人」与「代表人的应用」，不允许只留其一（延续 ADR-072、ADR-074）。

8. **权限与管理**：新增权限码 `fdn:appgrant:admin`；应用间授权的登记与撤销在高风险接口（如写操作、跨应用数据访问）时需安全管理员复核，与三员分立（ADR-053 第 1 节）一致。

9. **与既有设计的关系，不重叠**：
   - 不替代 CON-07a（业务系统以工具形态被 Agent 调用）：那是「Agent → 工具」，本 ADR 是「应用 → 应用」；
   - A2A（ADR-045）管跨组织 Agent 互操作，本 ADR 管企业内应用互调，二者可组合但边界清晰；
   - 应用间**事件订阅**的授权不在本 ADR（用事件契约与订阅白名单），本 ADR 只管请求-响应式调用。

10. **不做集中式应用网关**：不把 Foundation 做成所有应用间调用的代理。集中代理控制最强，但会让 Foundation 进入请求热路径，违反 ADR-051 第 5 条，也放大单点故障面。

## 备选

| 备选 | 不选原因 |
| --- | --- |
| 复用现有服务账号 + 应用角色（把「调 B」建模为服务账号在 B 应用里拥有某些权限码） | 实现最省，但表达不了「应用 A 代表用户」（无 OBO），也没有调用方收窄，审计里分不清是人还是应用在调 |
| Foundation 做集中式应用网关，代理所有应用间调用 | 控制最强，但 Foundation 进入请求热路径，与 ADR-051 第 5 条冲突，并放大单点故障面 |
| 各应用自行约定共享密钥/内网直连 | 无统一登记、无收窄、无审计，离职与撤销无法联动 |
| 只用 OBO、不做 M2M | 定时任务与后台流程没有用户身份可用，无法覆盖 |

## 后果

- Foundation 新增「应用授权」实体与 `fdn.app.grant.changed` 事件；新增权限码 `fdn:appgrant:admin`。
- `foundation-starter-security` 增加撤销与授权关系校验、`@RequireAppGrant`；`foundation-starter-client` 增加调用方 SDK（M2M/OBO）。
- Keycloak 需要开启 Token Exchange（RFC 8693）与 audience 映射；OBO 令牌新增 `act` 声明，不影响既有单应用令牌（向后兼容）。
- Tioka 与业务系统之间的调用、以及后续子应用之间的调用，统一走本机制；每个新调用方需在 Foundation 登记一次。
- 排期：F2 增强批次，后端 BAS-29（5）、BAS-30（5），前端 BAS-FE-15（3）；闸门 `GfAppGrantGateIT`。
