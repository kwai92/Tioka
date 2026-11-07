package com.tioka.orchestration.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.id.Ids;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.orchestration.infrastructure.OrchRuleRepository;
import com.tioka.orchestration.infrastructure.persistence.OrchRuleDO;
import com.tioka.orchestration.infrastructure.persistence.OrchRuleVersionDO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class OrchRuleAppService {
    private final OrchRuleRepository repo;
    private final CelRuleCondition cel;
    private final RuleActionValidator actionValidator;
    private final RuleEvalService evalService;
    private final PrincipalHolder principals;
    private final ObjectMapper mapper;

    public OrchRuleAppService(OrchRuleRepository repo,
                              CelRuleCondition cel,
                              RuleActionValidator actionValidator,
                              RuleEvalService evalService,
                              PrincipalHolder principals,
                              ObjectMapper mapper) {
        this.repo = repo;
        this.cel = cel;
        this.actionValidator = actionValidator;
        this.evalService = evalService;
        this.principals = principals;
        this.mapper = mapper;
    }

    @Transactional
    public RuleView createRule(String name) {
        Principal p = requirePrincipal();
        String id = Ids.newId("rule");
        OrchRuleDO row = new OrchRuleDO();
        row.setId(id);
        row.setName(name == null || name.isBlank() ? id : name);
        row.setWorkspaceId(p.workspaceId());
        row.setOwnerId(p.userId());
        repo.insertRule(row);
        return toRuleView(row, List.of());
    }

    @Transactional
    public VersionView createVersion(String ruleId, CreateVersionCommand cmd) {
        OrchRuleDO rule = requireRule(ruleId);
        String actionsJson = toJson(cmd.actions() == null ? List.of() : cmd.actions());
        actionValidator.validate(actionsJson, cmd.serviceAccountId());
        String celExpr = cmd.conditionCel() == null ? "true" : cmd.conditionCel();
        String versionId = Ids.newId("rv");
        cel.compileOrThrow(versionId, celExpr);

        OrchRuleVersionDO row = new OrchRuleVersionDO();
        row.setId(versionId);
        row.setRuleId(rule.getId());
        row.setVersion(repo.nextVersionNo(ruleId));
        row.setEventType(requireText(cmd.eventType(), "eventType"));
        row.setConditionCel(celExpr);
        row.setActionsJson(actionsJson);
        row.setConcurrency(normalizeEnum(cmd.concurrency(), "serial", "serial", "merge", "parallel"));
        row.setAcceptMode(normalizeEnum(cmd.acceptMode(), "observe", "off", "observe", "auto"));
        row.setTell(normalizeEnum(cmd.tell(), "needed", "always", "needed", "never"));
        row.setEmitsJson(cmd.emits() == null ? null : toJson(cmd.emits()));
        row.setScheduleJson(cmd.schedule() == null ? null : toJson(cmd.schedule()));
        row.setEscalateJson(cmd.escalate() == null ? null : toJson(cmd.escalate()));
        row.setServiceAccountId(cmd.serviceAccountId());
        row.setStage("DRAFT");
        row.setCanaryPercent(null);
        repo.insertVersion(row);
        return toVersionView(row, null, contentHash(row));
    }

    @Transactional
    public VersionView publish(String ruleId, String versionId, PublishCommand cmd) {
        OrchRuleDO rule = requireRule(ruleId);
        OrchRuleVersionDO version = requireVersion(ruleId, versionId);
        if (!"DRAFT".equals(version.getStage()) && !"CANARY".equals(version.getStage())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "仅 DRAFT/CANARY 可发布");
        }
        String stage = requireText(cmd.stage(), "stage").toUpperCase(Locale.ROOT);
        if (!"CANARY".equals(stage) && !"STABLE".equals(stage)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "stage 须为 CANARY 或 STABLE");
        }
        actionValidator.validate(version.getActionsJson(), version.getServiceAccountId());
        cel.compileOrThrow(version.getId(), version.getConditionCel());

        String previousEffective = "STABLE".equals(stage)
                ? rule.getStableVersionId()
                : rule.getCanaryVersionId();

        if ("CANARY".equals(stage)) {
            int percent = cmd.canaryPercent() == null ? 10 : cmd.canaryPercent();
            if (percent < 0 || percent > 100) {
                throw new BizException(ErrorCode.BAD_REQUEST, "canaryPercent 须在 0..100");
            }
            if (rule.getCanaryVersionId() != null && !rule.getCanaryVersionId().equals(versionId)) {
                retire(rule.getCanaryVersionId());
            }
            version.setStage("CANARY");
            version.setCanaryPercent(percent);
            repo.updateVersion(version);
            rule.setCanaryVersionId(versionId);
            repo.updateRule(rule);
        } else {
            if (rule.getStableVersionId() != null && !rule.getStableVersionId().equals(versionId)) {
                retire(rule.getStableVersionId());
            }
            if (rule.getCanaryVersionId() != null && rule.getCanaryVersionId().equals(versionId)) {
                rule.setCanaryVersionId(null);
            } else if (rule.getCanaryVersionId() != null) {
                // 提升其它 canary 为 stable 时，可保留 canary 指针；此处清空同版本
            }
            version.setStage("STABLE");
            version.setCanaryPercent(null);
            repo.updateVersion(version);
            rule.setStableVersionId(versionId);
            repo.updateRule(rule);
        }
        return toVersionView(version, previousEffective, contentHash(version), cmd.publishNote());
    }

    public DryRunResult dryRun(String ruleId, String versionId, Map<String, Object> event) {
        requireRule(ruleId);
        OrchRuleVersionDO version = requireVersion(ruleId, versionId);
        return evalService.dryRun(version, event == null ? Map.of() : event);
    }

    public List<RuleView> listRules() {
        return repo.listRules().stream()
                .map(r -> toRuleView(r, repo.listVersions(r.getId())))
                .toList();
    }

    public RuleView getRule(String ruleId) {
        OrchRuleDO rule = requireRule(ruleId);
        return toRuleView(rule, repo.listVersions(ruleId));
    }

    private void retire(String versionId) {
        OrchRuleVersionDO old = repo.findVersion(versionId);
        if (old == null) {
            return;
        }
        old.setStage("RETIRED");
        repo.updateVersion(old);
        cel.evict(versionId);
    }

    private OrchRuleDO requireRule(String ruleId) {
        OrchRuleDO rule = repo.findRule(ruleId);
        if (rule == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "rule 不存在");
        }
        return rule;
    }

    private OrchRuleVersionDO requireVersion(String ruleId, String versionId) {
        OrchRuleVersionDO version = repo.findVersion(versionId);
        if (version == null || !ruleId.equals(version.getRuleId())) {
            throw new BizException(ErrorCode.NOT_FOUND, "rule version 不存在");
        }
        return version;
    }

    private Principal requirePrincipal() {
        Principal p = principals.current();
        if (p == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "未登录");
        }
        return p;
    }

    private RuleView toRuleView(OrchRuleDO rule, List<OrchRuleVersionDO> versions) {
        return new RuleView(
                rule.getId(),
                rule.getName(),
                rule.getWorkspaceId(),
                rule.getOwnerId(),
                rule.getStableVersionId(),
                rule.getCanaryVersionId(),
                versions.stream().map(v -> toVersionView(v, null, contentHash(v))).toList());
    }

    private VersionView toVersionView(OrchRuleVersionDO v, String previousEffectiveId, String hash) {
        return toVersionView(v, previousEffectiveId, hash, null);
    }

    private VersionView toVersionView(OrchRuleVersionDO v, String previousEffectiveId,
                                      String hash, String publishNote) {
        return new VersionView(
                v.getId(),
                v.getRuleId(),
                v.getVersion() == null ? 0 : v.getVersion(),
                v.getEventType(),
                v.getConditionCel(),
                v.getActionsJson(),
                v.getConcurrency(),
                v.getAcceptMode(),
                v.getTell(),
                v.getServiceAccountId(),
                v.getStage(),
                v.getCanaryPercent(),
                hash,
                publishNote,
                previousEffectiveId,
                v.getCreateBy(),
                v.getCreateAt() == null ? null : v.getCreateAt().toString());
    }

    static String contentHash(OrchRuleVersionDO v) {
        String normalized = String.join("\n",
                nullToEmpty(v.getEventType()),
                nullToEmpty(v.getConditionCel()),
                nullToEmpty(v.getActionsJson()),
                nullToEmpty(v.getConcurrency()),
                nullToEmpty(v.getAcceptMode()),
                nullToEmpty(v.getTell()),
                nullToEmpty(v.getEmitsJson()),
                nullToEmpty(v.getServiceAccountId()));
        try {
            byte[] dig = MessageDigest.getInstance("SHA-256")
                    .digest(normalized.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(dig);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new BizException(ErrorCode.BAD_REQUEST, "JSON 序列化失败");
        }
    }

    private static String requireText(String v, String field) {
        if (v == null || v.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, field + " 必填");
        }
        return v;
    }

    private static String normalizeEnum(String raw, String defaultValue, String... allowed) {
        String v = raw == null || raw.isBlank() ? defaultValue : raw.toLowerCase(Locale.ROOT);
        for (String a : allowed) {
            if (a.equals(v)) {
                return v;
            }
        }
        throw new BizException(ErrorCode.BAD_REQUEST, "非法枚举值: " + raw);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    public record CreateVersionCommand(
            String eventType,
            String conditionCel,
            List<Map<String, Object>> actions,
            String concurrency,
            String acceptMode,
            String tell,
            List<String> emits,
            Map<String, Object> schedule,
            Map<String, Object> escalate,
            String serviceAccountId
    ) {}

    public record PublishCommand(String stage, Integer canaryPercent, String publishNote) {}

    public record RuleView(
            String id,
            String name,
            String workspaceId,
            String ownerId,
            String stableVersionId,
            String canaryVersionId,
            List<VersionView> versions
    ) {}

    public record VersionView(
            String id,
            String ruleId,
            int version,
            String eventType,
            String conditionCel,
            String actionsJson,
            String concurrency,
            String acceptMode,
            String tell,
            String serviceAccountId,
            String stage,
            Integer canaryPercent,
            String contentHash,
            String publishNote,
            String previousEffectiveVersionId,
            String publishedBy,
            String publishedAt
    ) {}

    public record DryRunResult(boolean matched, String decision, String reason, String versionId,
                               boolean fellBackToStable) {}
}
