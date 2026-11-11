package com.tioka.eval.web;

import com.tioka.eval.api.EvalFeedbackApi;
import com.tioka.framework.security.Permissions;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.RequirePermission;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * EVO-01 反馈接口（FE-26 消息点赞点踩与意见）。
 */
@RestController
@RequestMapping("/api/eval/feedback")
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        prefix = "tioka.eval", name = "enabled", havingValue = "true")
public class EvalFeedbackController {

    private final EvalFeedbackApi feedback;
    private final PrincipalHolder principals;

    public EvalFeedbackController(EvalFeedbackApi feedback, PrincipalHolder principals) {
        this.feedback = feedback;
        this.principals = principals;
    }

    public record FeedbackCmd(Integer rating, String comment, String turnId) {}

    @PostMapping
    @RequirePermission(Permissions.CHAT_USE)
    public Map<String, Object> submit(@RequestBody FeedbackCmd cmd) {
        String userId = principals.current().userId();
        feedback.feedback(cmd.turnId(), userId, cmd.rating() == null ? 0 : cmd.rating(), cmd.comment());
        return Map.of("ok", true);
    }
}
