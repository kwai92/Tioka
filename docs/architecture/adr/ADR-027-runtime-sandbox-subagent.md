# ADR-027 — Runtime 沙箱、子 Agent、上下文压缩与后台 Job

- 状态：Accepted（设计定案，待实现）
- 日期：2026-11-05
- 覆盖工作项：RT-09〜12
- 对应设计：设计 M
- 阅读版：[Tioka 推进规划与详细设计](https://claude.ai/artifact/V3wtabWCVSwAC3ewNerFNi)
- 约束：与 [ADR-029](ADR-029-design-rulings.md) 定案冲突时以定案为准；修改本 ADR 走 [ADR-030](ADR-030-anti-drift-process.md) 的变更流程。

覆盖 RT-09〜12 与 S1 中的沙箱、代码执行、子 Agent、上下文压缩、后台 Job。核心决定是：**所有长时间等待都用「挂起 Turn、释放 worker、事件唤醒」这一种机制**，和审批挂起一致。因此设计 B 的 Turn 状态机增加一个 `WAITING_CHILD`（等子 Agent 或后台 Job），这是对设计 B 的正式修订。

## 沙箱（RT-11）

```java
public interface SandboxProvider {                 // docker / k8s；微虚拟机后端可后续接入
    SandboxHandle acquire(SandboxSpec spec);
    ExecResult exec(SandboxHandle handle, ExecRequest request);
    void release(SandboxHandle handle);
}
record SandboxSpec(String threadId, String image, int cpuMilli, int memoryMb, Duration maxLifetime,
                   NetworkPolicy network,          // NONE / ALLOWLIST(hosts)
                   List<Mount> mounts) {}          // 会话工作盘挂到 /workspace
```

| 项 | 定案 |
| --- | --- |
| 后端 | 开发与 Compose 用 Docker；Helm 部署用 Kubernetes Pod，集群支持时使用 gVisor 运行时 |
| 生命周期 | 每个 thread 最多一个沙箱，第一次执行时创建；空闲 15 分钟回收，最长存活 8 小时；回收后工作盘保留，下次执行重新挂载 |
| 工作盘 | 按 ADR-014 的 uploads / skills / scratch / artifacts 目录，存在共享卷上；不同 thread 互不可见 |
| 网络 | 默认无网络；允许联网时只能经 Tioka 的出口代理，受 Agent 沙箱策略与 Skill 权限交集的白名单限制 |
| 镜像 | 内置 Python 数据分析镜像和 Node 镜像；离线安装包一并携带 |
| 底线 | 沙箱未启用的部署中，代码执行与脚本类工具一律不可用，绝不在应用进程内执行 |

## 代码执行工具

`code.run(language, code)` 在沙箱内运行，返回 stdout / stderr（各截断到 20KB）和退出码；写到 `/workspace/artifacts` 的文件自动变成 `file_ref` item 供下载。取代现在返回 `code.hashCode()` 的假实现。`shell.exec` 只对沙箱策略为 full 的 Agent 开放；交互式终端放在 M4 之后。

## 后台 Job 与长任务（RT-09）

- 工具可以把长命令转为后台 Job：立即返回 jobId，Job 在沙箱里继续跑。Agent 可选择等待（Turn 进入 `WAITING_CHILD`）或继续做别的。
- Job 结束发事件：等待中的 Turn 被唤醒并拿到结果；已结束的 Turn 则按 Agent 配置在同一 thread 开一个新 Turn（origin=job）或仅通知用户。
- 默认时限：工具调用 120 秒（最大 30 分钟），单个 Turn 2 小时，Job 8 小时。取代现在写库即完成的 `AutomationJobService`。

## 子 Agent（RT-12）

1. 内置工具 `agent.delegate(agentId, task, context)`，只能委派 AgentSpec 里 `subAgents` 允许的 Agent。
2. 创建子 thread（记录父 Turn 和深度），用同一个 Principal 开子 Turn；父 Turn 进入 `WAITING_CHILD`，不占 worker。
3. 深度默认上限 2，同一步最多并行委派 3 个；子 Turn 的 token 计入父 Turn 的预算，超出则子 Turn 被取消。
4. 子 Turn 完成后，其最终回答作为委派工具的结果回到父 Turn，轨迹视图可点进子 thread。父 Turn 被取消时级联取消子 Turn。

S1 的 Agent Team（任务板与角色协作）建立在委派之上，放在 M4 之后单独设计。

## 上下文组装与压缩（RT-10）

**组装顺序固定**，各模块不得自行插入：平台安全指令 → Agent 指令 → always Skill → auto Skill 索引 → 用户记忆 → 知识自动检索结果 → 压缩摘要 → 历史 item → 本轮输入。

每个 item 入库时记录 token 数。每次调模型前估算总量，超过所选模型上下文的 75% 就压缩：保留最近 3 个 Turn，更早的内容经 `ModelGateway`（purpose=compaction）摘要成一个压缩 item，原始 item 仍保留供界面展示。单个工具结果超过 8000 token 时，全文写入工作盘，提示里只放开头部分和文件路径。压缩后允许重新选模（设计 J）。

## 数据模型

```sql
ALTER TABLE runtime_item ADD COLUMN token_count INT;
ALTER TABLE runtime_session ADD COLUMN parent_turn_id VARCHAR(32);     -- 子 Agent 的父 Turn
ALTER TABLE runtime_session ADD COLUMN depth INT NOT NULL DEFAULT 0;
CREATE TABLE runtime_sandbox (id VARCHAR(32) PRIMARY KEY, thread_id VARCHAR(64) NOT NULL UNIQUE,
  provider VARCHAR(16) NOT NULL, handle VARCHAR(255) NOT NULL, status VARCHAR(16) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL, last_used_at TIMESTAMPTZ NOT NULL);
CREATE TABLE runtime_job (id VARCHAR(32) PRIMARY KEY, thread_id VARCHAR(64) NOT NULL, turn_id VARCHAR(32) NOT NULL,
  command TEXT NOT NULL, status VARCHAR(16) NOT NULL, exit_code INT, output_ref VARCHAR(512),
  on_complete VARCHAR(16) NOT NULL,                -- RESUME / NEW_TURN / NOTIFY
  started_at TIMESTAMPTZ NOT NULL, ended_at TIMESTAMPTZ);
-- automation_job、agent_sandbox 废弃
```

## 修订（2026-11-05）

上下文组装顺序加入「经验」，见 [ADR-029](ADR-029-design-rulings.md) 第 25 条与 [ADR-031](ADR-031-agent-eval-experience.md)。

子 Agent（RT-12）提前到 M3，深度与并行数改为缺省值，委派时签发收窄的授予，子任务终态时回收，见 [ADR-029](ADR-029-design-rulings.md) 第 43 条、[ADR-038](ADR-038-multi-agent-team.md)、[ADR-039](ADR-039-delegation-grant.md)。「Agent Team」的设计即 ADR-038。
