package com.tioka.runtime.api.kernel;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Runtime 内核唯一对外入口（ADR-016）。
 *
 * <p>调用方身份取自 {@code PrincipalHolder}；startTurn 写入 run_as 后立即返回，
 * 执行在 worker 上异步进行，结果通过 SSE {@code GET /api/runtime/threads/{id}/events} 推送。
 */
public interface RuntimeApi {

    ThreadView createThread(CreateThreadCmd cmd);

    /**
     * 开始一个 Turn。按 {@code (threadId, clientMsgId)} 幂等：重复调用返回已有 Turn。
     * 同一 thread 已有运行中 Turn 时排队（FIFO，最多 5 条，超出抛 {@code RATE_LIMITED}，ADR-029 第 5 条）。
     */
    TurnHandle startTurn(String threadId, StartTurnCmd cmd);

    void interrupt(String turnId);

    /** 插话：内容在下一次模型调用前作为用户 item 注入。 */
    void steer(String turnId, String text);

    /** 审批提交；发 {@code approval.decided}，由对应 subject 的处理器续跑。 */
    void decideApproval(String approvalId, ApprovalDecision decision);

    TurnView getTurn(String turnId);

    /**
     * 当前 thread 上最新的非终态 Turn（QUEUED / RUNNING / WAITING_*）；无则 empty。
     * Chat {@code /stop} 等入口据此转调 {@link #interrupt(String)}。
     */
    Optional<TurnView> findActiveTurn(String threadId);

    /** 按 seq 升序返回 {@code afterSeq} 之后的 item；SSE 断线补偿也走这里。 */
    List<ItemView> listItems(String threadId, long afterSeq, int limit);

    /** 在 {@code atSeq} 处分叉出新 thread（M3，Chat 真分叉，ADR-022）。 */
    ThreadView fork(String threadId, long atSeq);

    // ---- 命令 ----

    /**
     * @param agentVersionId 已发布的 Agent 版本快照
     * @param origin         chat / flow / orch / im / job
     * @param originRef      来源对象 ID，如 flow 节点运行 ID
     * @param metadata       业务元数据，如 Flow 的 runId、nodeId（ADR-029 第 11 条）
     */
    record CreateThreadCmd(String agentVersionId, String origin, String originRef,
                           String title, Map<String, String> metadata) {}

    /**
     * MAS-01：{@code onBehalfOf} SERVICE 模式代表经办人（全链不变）；{@code mandateScope}
     * 编排 {@code orch_mandate.scope_json} 解析出的任务范围。旧 5 参构造兼容（两者为空）。
     */
    record StartTurnCmd(String clientMsgId, List<InputItem> input, TurnOptions options,
                        String origin, String originRef,
                        String onBehalfOf, Map<String, List<String>> mandateScope) {
        public StartTurnCmd(String clientMsgId, List<InputItem> input, TurnOptions options,
                            String origin, String originRef) {
            this(clientMsgId, input, options, origin, originRef, null, null);
        }
    }

    /**
     * @param maxSteps       默认 20
     * @param approvalPolicy 审批策略编码
     * @param toolAllowList  本 Turn 可用工具白名单；null 表示按 Agent 配置
     * @param maxDuration    时长上限（RT-09）；null 用默认（通常 30 分钟）
     */
    record TurnOptions(Integer maxSteps, String approvalPolicy, List<String> toolAllowList,
                       java.time.Duration maxDuration) {
        public TurnOptions(Integer maxSteps, String approvalPolicy, List<String> toolAllowList) {
            this(maxSteps, approvalPolicy, toolAllowList, null);
        }
    }

    /** 输入项；{@code type} 取 message / file_ref。 */
    record InputItem(String type, String text, String fileRef) {
        public static InputItem text(String text) {
            return new InputItem(ItemType.MESSAGE.wire(), text, null);
        }
    }

    /**
     * @param approved 是否批准
     * @param answer   卡片答复或修改后的工具参数
     * @param comment  备注
     */
    record ApprovalDecision(boolean approved, Map<String, Object> answer, String comment) {}

    // ---- 视图 ----

    record ThreadView(String threadId, String agentVersionId, String origin, String originRef,
                      String parentTurnId, int depth, long lastItemSeq, Instant createdAt) {}

    record TurnHandle(String turnId, TurnStatus status, String eventsUrl) {}

    record TurnView(String turnId, String threadId, int seq, TurnStatus status, String origin,
                    String originRef, int stepCount, String errorCode, String errorMessage,
                    Integer promptTokens, Integer completionTokens,
                    Instant createdAt, Instant startedAt, Instant endedAt) {}

    /** {@code seq} 即 SSE 事件 ID 的整数部分。 */
    record ItemView(String itemId, String threadId, String turnId, long seq, int step,
                    ItemType type, String role, Map<String, Object> content, Instant createdAt) {}
}
