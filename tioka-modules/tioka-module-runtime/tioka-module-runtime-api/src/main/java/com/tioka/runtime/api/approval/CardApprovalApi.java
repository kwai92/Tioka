package com.tioka.runtime.api.approval;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 卡片人审（kind=card）的创建入口：编排 ORC-04、Flow FLW-03 经本接口写 {@code runtime_approval}，
 * 不经 Turn 工具挂起路径。实现落在 runtime-biz，不改审批决定逻辑（K2）。
 */
public interface CardApprovalApi {

    /**
     * 打开或复用待决卡片审批。
     *
     * <p>同 {@code subjectType+subjectId+key} 且仍为 PENDING 时复用原行，不重发事件。
     */
    ApprovalView openOrReuse(OpenCardCommand cmd);

    /** 查某主体下指定 key 的 PENDING 审批（若有）。 */
    Optional<ApprovalView> findPending(String subjectType, String subjectId, String key);

    /**
     * @param key         业务幂等键（ask.key / HumanCard wait key）
     * @param title       审批台标题
     * @param form        表单字段定义
     * @param summary     摘要，可空
     * @param assignee    userIds / roleCodes
     * @param timeout     过期时长；null 默认 24h
     * @param onTimeout   超时默认答案，写入 card_json 供 EXPIRED 续跑；可空
     * @param requestedBy 发起人；可空
     */
    record OpenCardCommand(
            String workspaceId,
            String subjectType,
            String subjectId,
            String key,
            String title,
            String summary,
            List<Map<String, Object>> form,
            Map<String, List<String>> assignee,
            Duration timeout,
            Map<String, Object> onTimeout,
            String requestedBy) {}
}
