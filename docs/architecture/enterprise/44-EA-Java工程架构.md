# EA — Tioka Java 工程架构

> 回答：Maven 多模块怎么切、依赖与包规范、组件如何装配、与 RuoYi 对齐点。  
> 运行时依赖图见 `docs/architecture/module-dependencies.md`（须与本文同步）。

---

## 1. 仓库布局

```
tioka/
├── tioka-dependencies/          # BOM：版本锁定
├── tioka-framework/             # 安全上下文、错误码、Trace、基类
├── tioka-modules/
│   ├── tioka-module-system/     # api + biz（W0–W1 含 foundation 合部）
│   └── （可选拆分）tioka-module-foundation/  # W2+：SPI/SSO/组织同步独立模块
│   ├── tioka-module-infra/
│   ├── tioka-module-runtime/
│   ├── tioka-module-orchestration/
│   ├── tioka-module-chat/
│   ├── tioka-module-knowledge/
│   ├── tioka-module-flow/
│   ├── tioka-module-connector/
│   ├── tioka-module-model/
│   ├── tioka-module-agent/
│   ├── tioka-module-mcp/
│   └── tioka-module-skill/
├── tioka-server/                # 单体启动（聚合 biz）
├── tioka-server-*/              # 可选拆分进程（W3+）
└── tioka-ui/                    # 前端（见 FE）
```

每个业务模块：

```
tioka-module-foo/
├── tioka-module-foo-api/        # 接口、DTO、MQ 常量、SPI（若对外）
└── tioka-module-foo-biz/        # 实现；只依赖 *-api 与 framework
```

---

## 2. 依赖规则（硬约束）

| 规则 | 说明 |
| --- | --- |
| R1 | `*-biz` **禁止** 依赖其他 `*-biz` |
| R2 | `*-biz` 只依赖本域或其他域的 `*-api` + `tioka-framework` + 中间件 starter |
| R3 | `runtime-biz` **禁止** 直接依赖 `connector` 实现；工具经 SPI/描述符 |
| R4 | MQ Topic/Tag **只** 定义在 `*-api` 常量类 |
| R5 | 版本号只在 `tioka-dependencies` BOM 声明；模块不写散落 version |
| R6 | 禁止 SNAPSHOT 进生产 BOM（工程规范） |

ArchUnit 用例在 W0 落地（见 31 W0-09）。

---

## 3. 允许依赖矩阵（摘要）

| biz | 可依赖 api |
| --- | --- |
| system | framework |
| infra | system-api |
| model | system-api |
| runtime | system-api, model-api |
| agent | system, model, mcp, skill, flow-api |
| mcp / skill | system-api |
| orchestration | system, runtime-api |
| chat | system, runtime, orch, agent-api |
| knowledge | system, infra, connector-api |
| flow | system, runtime, orch, model, knowledge(可选) |
| connector | system, orch, knowledge-api |

完整图：module-dependencies.md。

---

## 4. 包结构约定（biz 内）

```
com.tioka.<domain>
├── api 适配     controller / rpc / mq consumer（薄）
├── application  应用服务（事务边界通常在此）
├── domain       模型、领域服务、策略
├── infrastructure  repository、外部 client 封装
└── config       Spring 配置
```

命名：`XxxService` / `XxxRepository` / `XxxController`；DTO 在 api 模块 `dto` 包。

---

## 5. 组件与扩展点

| 扩展点 | 位置 | 说明 |
| --- | --- | --- |
| ToolProvider | runtime-api SPI | KB/Flow/MCP/Builtin 实现方注册 |
| ModelProviderAdapter | model | OpenAI 兼容等 |
| ImChannelAdapter 等 | foundation-api / connector | S9 SPI |
| ContentSafetyHook | system | 可 Noop |
| DecisionStrategy | model | 难度启发式/决策模型 |
| NodeHandler | flow | 节点类型插件 |
| SkillScriptExecutor | runtime | 沙箱执行 |

注册：Spring `ListableBean` / `spring.factories` / 显式 Registry；客户 jar 热加载目录（S9）。

---

## 6. 事务、线程与异步

- **事务**：应用服务层 `@Transactional`；跨域不分布式事务，用 MQ 最终一致。  
- **Runtime 循环**：避免在持有 DB 长事务中调模型；Turn 状态短事务提交。  
- **SSE**：独立写出；断开取消协作（Steer/Cancel）。  
- **MQ 消费**：线程池隔离；慢消费者不堵核心 API。  
- **定时**：infra Job 或 MQ delay（Orch later）。

---

## 7. BOM / Starter 清单（建议锁定）

| 组件 | 用途 |
| --- | --- |
| spring-boot-starter-web | REST |
| spring-boot-starter-validation | 校验 |
| mybatis-plus-boot-starter | 持久化 |
| redis / redisson（择一） | 缓存锁 |
| rocketmq-spring / kafka | MQ（二选一） |
| spring-boot-starter-actuator | 健康 |
| micrometer-otel | 观测 |
| **sa-token**（默认，对齐 RuoYi-Vue-Pro） | 认证授权 |
| flyway/liquibase | 迁移 |
| archunit | 架构测试 |
| testcontainers（可选） | 集成测 |

具体版本写入 `tioka-dependencies`，评审后冻结。

---

## 8. 启动装配

`tioka-server`：

```java
@SpringBootApplication
@EnableXxx  // 按需
public class TiokaServerApplication { ... }
```

- profile：`local|dev|test|stage|prod`  
- 数据源/Redis/MQ 全可外置  
- 功能开关：SSO、空气间隙、解释器  

拆分进程时：每个 server 只扫对应 biz 包；共用 framework 与 DB 策略按 DA。

---

## 9. API 暴露形态

| 形态 | 实现 |
| --- | --- |
| HTTP | `*-controller` 实现 api 接口或委托 application |
| 本地 Bean | 其他 biz 注入 `XxxApi` 接口（实现类在提供方 biz） |
| 未来 Feign | api 模块加 Feign 客户端接口，biz 不变 |

`XxxApi` 接口**必须**落在 api 模块。

---

## 10. 测试金字塔

| 层 | 要求 |
| --- | --- |
| 单测 | 领域策略、路由、幂等 |
| ArchUnit | R1–R4 |
| 契约测 | api 签名 + Part schema |
| 切片测 | `@SpringBootTest` + Testcontainers |
| E2E | 31 黄金路径 |

---

## 11. 与 RuoYi-Vue-Pro 对齐点

- 多模块 api/biz、权限模型、代码风格可借鉴  
- **不**把业务写进 RuoYi 示例模块；Tioka 域独立  
- 前端基线可复用布局/权限指令，HCI Part 自研（FE）  

---

## 12. 波次落地

W0：dependencies/framework/system/infra/空模块/server/ArchUnit  
W1：model/runtime/agent/mcp/chat  
W2：orch/connector/skill  
W3：knowledge  
W4：flow  
W5：拆分 server 与插件加载完备
