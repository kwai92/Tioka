package com.tioka.eval.api;

/**
 * 用户反馈端口（EVO-01 / ADR-031：点赞、点踩并写意见 → eval_feedback）。
 * 撤销（TRU-07）与法务驳回经事件解耦自动写负向反馈（source 区分），不走本端口。
 */
public interface EvalFeedbackApi {

    /** rating：+1 点赞 / -1 点踩；同 (turnId, userId) 已有反馈时保留原反馈（UNIQUE 幂等）。 */
    void feedback(String turnId, String userId, int rating, String comment);
}
