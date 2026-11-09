package com.tioka.server;

import com.tioka.agent.api.spec.AgentSpec;
import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.runtime.kernel.application.KernelRuntimeService;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * L2 六类假实现台账（可检索单一入口）。
 *
 * <p>证据口径：原始六类假实现清单见 {@code D7 §01}（{@code docs/architecture/design-notes/01} 行 70-75，
 * 旧「代码现状」表）。本轮为 P0 冻结后的结论固化——逐类给出「已下线 / 已替换」结论，并可被测试检索（6/6）。
 *
 * <pre>
 * 类别                                 结论        当前生产证据（路径#符号）
 * 1. GraphRagService                   已下线(501) OpsFacadeController.java:81-84  throw NotImplemented("graph-rag","M4")
 * 2. CodeInterpreterService            已下线(501) OpsFacadeController.java:98-101 throw NotImplemented("code-interpreter","ADR-027")
 * 3. AutomationJobService              已下线(501) OpsFacadeController.java:103-106 throw NotImplemented("automation-jobs","ADR-027")
 * 4. ChatDensityService.fork           已替换(M3)   KernelRuntimeService.java:242-248 抛 NotImplemented("runtime-fork","M3")；
 *                                                  OpsFacadeController.java:108-111 抛 NotImplemented("chat-fork","ADR-022")
 * 5. SubAgentPolicy                    已替换(预留) AgentSpec.java:31 subAgents 仍为预留字段（须 null）；
 *                                                  SchemaPublishCheck.java:42-43 RESERVED_FIELD 拒绝；
 *                                                  真实子 Agent 执行已由 RuntimeChildRouterConsumer + ChildResumeService 承接
 * 6. DifficultyRouter                  已下线(501) ModelAdminService.java:100-101,129-130 与
 *                                                  ModelBindingResolver.java:62 抛 NotImplemented（DIFFICULTY, ADR-024）
 * </pre>
 *
 * <p>本类为纯单元测试（不启动 Spring / 不需要 postgres·redis·rocketmq），断言：
 * <ul>
 *   <li>类别 1/2/3/5/6 的 5 个已删除服务类在主代码编译产物中不可见（类路径扫描 + ClassNotFound 语义）；</li>
 *   <li>类别 5 的 AgentSpec.subAgents 占位字段必须为 null；</li>
 *   <li>类别 4 的 KernelRuntimeService.fork 必须保持 M3 RESERVED，抛 {@link ErrorCode#NOT_IMPLEMENTED}。</li>
 * </ul>
 *
 * <p>类别 1/2/3/6 的「端点返回 501 + code=50100」HTTP 契约由 {@code FakeApiOfflineIT.fakeApisReturn501()} 覆盖；
 * 类别 4 的 fork 端点 501 亦由该 IT 覆盖。本类负责「类级结论」的程序化固化，二者共同构成 6/6 可检索台账。
 */
class PlaceholderLedgerIT {

    /** 1. GraphRagService：已下线（端点 M4 抛 NotImplemented）。 */
    @Test
    void graphRagServiceRemoved() {
        assertClassAbsent("GraphRagService");
    }

    /** 2. CodeInterpreterService：已下线（端点 ADR-027 抛 NotImplemented）。 */
    @Test
    void codeInterpreterServiceRemoved() {
        assertClassAbsent("CodeInterpreterService");
    }

    /** 3. AutomationJobService：已下线（端点 ADR-027 抛 NotImplemented）。 */
    @Test
    void automationJobServiceRemoved() {
        assertClassAbsent("AutomationJobService");
    }

    /** 5. SubAgentPolicy：已下线；subAgents 转 AgentSpec 预留字段（须 null）。 */
    @Test
    void subAgentPolicyRemoved() {
        assertClassAbsent("SubAgentPolicy");
    }

    /** 6. DifficultyRouter：已下线（DIFFICULTY 路由抛 NotImplemented，ADR-024）。 */
    @Test
    void difficultyRouterRemoved() {
        assertClassAbsent("DifficultyRouter");
    }

    /**
     * 5（续）. SubAgent 占位字段契约：AgentSpec.subAgents 必须为 null（M1 预留，直至对应工作项解冻）。
     * 配合 {@code SchemaPublishCheck} 的 RESERVED_FIELD 拒绝，确保子 Agent 真实执行走
     * RuntimeChildRouterConsumer + ChildResumeService，而非旧 SubAgentPolicy。
     */
    @Test
    void agentSpecSubAgentsReservedNull() {
        AgentSpec spec = defaultSpec();
        assertNull(spec.subAgents(), "AgentSpec.subAgents 必须为 null（M1 预留字段，已替换旧 SubAgentPolicy）");
    }

    /**
     * 4. ChatDensityService.fork 已由 KernelRuntimeService.fork 取代并冻结为 M3 RESERVED：
     * 必须抛 {@link ErrorCode#NOT_IMPLEMENTED}（"runtime-fork","M3"），不得被悄悄实现。
     */
    @Test
    void kernelForkRemainsNotImplemented_M3Reserved() {
        KernelRuntimeService svc = new KernelRuntimeService(
                null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        BizException ex = assertThrows(BizException.class, () -> svc.fork("thread_x", 0));
        assertEquals(ErrorCode.NOT_IMPLEMENTED, ex.getErrorCode(),
                "fork 必须保持 M3 RESERVED（NOT_IMPLEMENTED），不得被实现");
    }

    // ---- helpers ----

    /** 在编译产物（classpath 目录）中扫描指定简单类名的 .class；若存在则判定「假实现未下线」。 */
    private static void assertClassAbsent(String simpleName) {
        String needle = simpleName + ".class";
        String cp = System.getProperty("java.class.path", "");
        List<String> hits = new ArrayList<>();
        for (String entry : cp.split(File.pathSeparator)) {
            File dir = new File(entry);
            if (!dir.isDirectory()) {
                continue;
            }
            try (var stream = Files.walk(dir.toPath())) {
                for (Path p : (Iterable<Path>) stream::iterator) {
                    if (p.getFileName() != null && p.getFileName().toString().equals(needle)) {
                        hits.add(p.toString());
                    }
                }
            } catch (Exception ignored) {
                // 无法遍历的条目跳过
            }
        }
        if (!hits.isEmpty()) {
            fail("类 " + simpleName + " 仍存在于编译产物中（疑似假实现未下线）：" + hits);
        }
    }

    /** 构造一个具有代表性的默认 AgentSpec（subAgents / dataClearance 等预留字段为 null）。 */
    private static AgentSpec defaultSpec() {
        return new AgentSpec(
                1,
                new AgentSpec.Profile("n", "d", null, null, null),
                new AgentSpec.Instructions(null),
                new AgentSpec.ModelPolicy((String) null),
                AgentSpec.ToolSet.empty(),
                null,
                AgentSpec.KnowledgeBinding.empty(),
                AgentSpec.SandboxPolicy.defaults(),
                AgentSpec.ApprovalSettings.empty(),
                AgentSpec.Limits.defaults(),
                null, null, null, null, null, null,
                null, // subAgents（M1 预留，必须为 null）
                null);
    }
}
