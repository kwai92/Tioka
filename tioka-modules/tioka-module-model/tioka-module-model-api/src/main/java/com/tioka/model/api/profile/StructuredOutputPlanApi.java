package com.tioka.model.api.profile;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * SCH-06（ADR-066 §2）：Agent 发布期结构化输出试编译。
 *
 * <p>按路由计划枚举候选模型，用权威 Schema 逐个试编译方言：编译失败的候选从该 Agent
 * 的结构化输出候选中排除；全部候选都失败则发布被拒。
 */
public interface StructuredOutputPlanApi {

    /**
     * @param routingPlanId       路由计划；不可用/未接线时返回空清单
     * @param authoritativeSchema 权威 Schema（与运行期同一份）
     * @return 候选模型的试编译结果；无法枚举候选（计划缺失或能力记录未接线）时返回空清单，
     *         调用方据此跳过发布期试编译
     */
    List<CandidateCompile> trialCompile(String routingPlanId, JsonNode authoritativeSchema,
                                        boolean multimodalInput);

    /** 单个候选模型的试编译结果；{@code compiled=false} 时 {@code reasonCode} 说明原因。 */
    record CandidateCompile(String modelRef, boolean compiled, String dialectHash, String reasonCode) {
    }
}
