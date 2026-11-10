package com.tioka.governance.application.label;

import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.label.DataLabel;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.governance.api.label.SinkCheckResult;
import com.tioka.governance.api.label.SinkGuard;
import com.tioka.governance.api.label.SinkKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * 去向许可判定（DAT-02 / M3-02「去向判定」伪码；ADR-040 规则 3）。
 *
 * <pre>
 * rule = gov_label_sink(ws, kind, ref) ?? gov_label_sink(ws, kind, '*') ?? 缺省
 * if label &lt;= rule.max_label: PASS
 * switch rule.on_exceed:
 *   DENY                → DENY
 *   REDACT_THEN_DENY    → p2 = RegexPiiHook.redact(payload)；p2 已无命中 → REDACTED(p2)，否则 DENY
 *   REDACT_THEN_APPROVE → 同上，否则 REQUIRE_APPROVAL
 * </pre>
 *
 * <p>缺省规则 {@code tioka.governance.label-sink-defaults.<KIND>}（M3-02 原文）：
 * MODEL_PLAN=INTERNAL；IM=CONFIDENTIAL（内部群口径，ref 级外部群收紧由空间配置行承载）；
 * MAIL / SMS / MCP=INTERNAL；其余（BIZ / FILE_EXPORT / OTHER）=INTERNAL。
 * 空间管理员按客户合规写入 gov_label_sink 行放宽或收紧。
 *
 * <p>脱敏落点（M3）：REDACT 路径内联调用 {@link RegexPiiHook}；护栏管线 SINK 检查点本体在 M4，
 * 登记偏差于批次文档。
 */
@Component
public class DefaultSinkGuard implements SinkGuard {

    private static final Logger log = LoggerFactory.getLogger(DefaultSinkGuard.class);

    private final LabelSinkRepository repository;
    private final PrincipalHolder principals;
    private final UnaryOperator<String> redactor;
    private final Map<SinkKind, DataLabel> defaults = new EnumMap<>(SinkKind.class);

    @org.springframework.beans.factory.annotation.Autowired
    public DefaultSinkGuard(LabelSinkRepository repository,
                            PrincipalHolder principals,
                            @Value("${tioka.governance.label-sink-defaults.MODEL_PLAN:INTERNAL}") String modelPlan,
                            @Value("${tioka.governance.label-sink-defaults.IM:CONFIDENTIAL}") String im,
                            @Value("${tioka.governance.label-sink-defaults.MAIL:INTERNAL}") String mail,
                            @Value("${tioka.governance.label-sink-defaults.SMS:INTERNAL}") String sms,
                            @Value("${tioka.governance.label-sink-defaults.MCP:INTERNAL}") String mcp,
                            @Value("${tioka.governance.label-sink-defaults.BIZ:INTERNAL}") String biz,
                            @Value("${tioka.governance.label-sink-defaults.FILE_EXPORT:INTERNAL}") String fileExport,
                            @Value("${tioka.governance.label-sink-defaults.OTHER:INTERNAL}") String other) {
        this(repository, principals, null, defaultsOf(modelPlan, im, mail, sms, mcp, biz, fileExport, other));
    }

    DefaultSinkGuard(LabelSinkRepository repository,
                     PrincipalHolder principals,
                     UnaryOperator<String> redactor,
                     Map<SinkKind, DataLabel> defaults) {
        this.repository = repository;
        this.principals = principals;
        this.redactor = redactor == null ? RegexPiiHook::redact : redactor;
        this.defaults.putAll(defaults);
    }

    private static Map<SinkKind, DataLabel> defaultsOf(String modelPlan, String im, String mail,
                                                       String sms, String mcp, String biz,
                                                       String fileExport, String other) {
        Map<SinkKind, DataLabel> m = new EnumMap<>(SinkKind.class);
        m.put(SinkKind.MODEL_PLAN, DataLabel.valueOf(modelPlan));
        m.put(SinkKind.IM, DataLabel.valueOf(im));
        m.put(SinkKind.MAIL, DataLabel.valueOf(mail));
        m.put(SinkKind.SMS, DataLabel.valueOf(sms));
        m.put(SinkKind.MCP, DataLabel.valueOf(mcp));
        m.put(SinkKind.BIZ, DataLabel.valueOf(biz));
        m.put(SinkKind.FILE_EXPORT, DataLabel.valueOf(fileExport));
        m.put(SinkKind.OTHER, DataLabel.valueOf(other));
        return Map.copyOf(m);
    }

    @Override
    public SinkCheckResult check(SinkKind kind, String sinkRef, DataLabel label, Object payload) {
        String workspaceId = principals.find().map(Principal::workspaceId).orElse(null);
        Rule rule = resolve(workspaceId, kind, sinkRef);
        if (label == null || label.atMost(rule.maxLabel)) {
            return SinkCheckResult.pass();
        }
        String reason = "label " + label + " 超出去向 " + kind + "/" + sinkRef
                + " 许可 " + rule.maxLabel;
        return switch (rule.onExceed) {
            case DENY -> {
                log.info("SinkGuard DENY ws={} kind={} ref={} label={}", workspaceId, kind, sinkRef, label);
                yield SinkCheckResult.deny(reason);
            }
            case REDACT_THEN_DENY -> redactOr(kind, sinkRef, label, payload,
                    reason, p -> SinkCheckResult.deny(reason + "（脱敏后仍含敏感内容）"));
            case REDACT_THEN_APPROVE -> redactOr(kind, sinkRef, label, payload,
                    reason, p -> SinkCheckResult.requireApproval(reason + "（转人工审批 data_egress）"));
        };
    }

    private SinkCheckResult redactOr(SinkKind kind, String sinkRef, DataLabel label, Object payload,
                                     String reason,
                                     java.util.function.Function<String, SinkCheckResult> fallback) {
        String text = payload instanceof String s ? s : String.valueOf(payload);
        String p2 = redactor.apply(text == null ? "" : text);
        if (!p2.isEmpty() && RegexPiiHook.hitCounts(p2).isEmpty()) {
            return SinkCheckResult.redacted(p2, reason + "（PII 脱敏后放行）");
        }
        return fallback.apply(text);
    }

    /** rule(ws, kind, ref) ?? rule(ws, kind, '*') ?? 缺省（M3-02 伪码）。 */
    Rule resolve(String workspaceId, SinkKind kind, String sinkRef) {
        if (workspaceId != null) {
            Optional<LabelSinkRepository.LabelSinkRule> exact =
                    repository.find(workspaceId, kind, sinkRef == null ? "" : sinkRef);
            if (exact.isPresent()) {
                return Rule.of(exact.get());
            }
            Optional<LabelSinkRepository.LabelSinkRule> wildcard =
                    repository.find(workspaceId, kind, "*");
            if (wildcard.isPresent()) {
                return Rule.of(wildcard.get());
            }
        }
        return new Rule(defaults.getOrDefault(kind, DataLabel.INTERNAL), OnExceed.DENY);
    }

    enum OnExceed {
        REDACT_THEN_DENY,
        REDACT_THEN_APPROVE,
        DENY;

        static OnExceed parse(String raw) {
            try {
                return valueOf(String.valueOf(raw).trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new BizException(ErrorCode.BAD_REQUEST, "未知 on_exceed: " + raw);
            }
        }
    }

    record Rule(DataLabel maxLabel, OnExceed onExceed) {
        static Rule of(LabelSinkRepository.LabelSinkRule r) {
            return new Rule(DataLabel.valueOf(r.maxLabel()), OnExceed.parse(r.onExceed()));
        }
    }
}
