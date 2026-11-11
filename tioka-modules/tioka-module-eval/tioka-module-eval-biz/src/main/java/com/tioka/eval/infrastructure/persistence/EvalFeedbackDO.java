package com.tioka.eval.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tioka.framework.persistence.BaseDO;

/**
 * EVO-01 / V212：用户反馈（ADR-031）。UNIQUE (turn_id, user_id)；
 * source 区分 USER / UNDO / REVIEW 三来源（撤销与法务驳回自动负向反馈）。
 */
@TableName("eval_feedback")
public class EvalFeedbackDO extends BaseDO {
    @TableId(type = IdType.INPUT)
    private String id;
    private String turnId;
    private String userId;
    /** +1 点赞 / -1 点踩 */
    private Integer rating;
    private String comment;
    /** USER / UNDO / REVIEW */
    private String source;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTurnId() { return turnId; }
    public void setTurnId(String turnId) { this.turnId = turnId; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public Integer getRating() { return rating; }
    public void setRating(Integer rating) { this.rating = rating; }
    public String getComment() { return comment; }
    public void setComment(String comment) { this.comment = comment; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
}
