package com.tioka.orchestration.application.dispatch;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.framework.error.BizException;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.orchestration.api.dispatch.OrchActionDispatcher;
import com.tioka.orchestration.api.dispatch.OrchActionType;
import com.tioka.orchestration.api.dispatch.OrchDispatchCommand;
import com.tioka.orchestration.api.engine.OrchApi;
import com.tioka.orchestration.application.pause.RulePauseService;
import com.tioka.orchestration.infrastructure.OrchEngineRepository;
import com.tioka.orchestration.infrastructure.persistence.OrchEventDO;
import com.tioka.orchestration.infrastructure.persistence.OrchMandateDO;
import com.tioka.orchestration.infrastructure.persistence.OrchRuleVersionDO;
import com.tioka.orchestration.infrastructure.persistence.OrchRunDO;
import com.tioka.orchestration.infrastructure.persistence.OrchWorkItemDO;
import com.tioka.system.api.spi.PrincipalLoaderApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 派工核心：Mandate 复验 + 按动作类型分派（ORC-03 / F23）。
 */
@Service
public class OrchDispatchService {
    private static final Logger log = LoggerFactory.getLogger(OrchDispatchService.class);

    private final OrchEngineRepository repo;
    private final PrincipalLoaderApi principalLoader;
    private final PrincipalHolder principals;
    private final ObjectMapper mapper;
    private final Map<OrchActionType, OrchActionDispatcher> dispatchers;
    private final RulePauseService rulePause;

    public OrchDispatchService(OrchEngineRepository repo,
                               PrincipalLoaderApi principalLoader,
                               PrincipalHolder principals,
                               ObjectMapper mapper,
                               List<OrchActionDispatcher> dispatcherList,
                               RulePauseService rulePause) {
        this.repo = repo;
        this.principalLoader = principalLoader;
        this.principals = principals;
        this.mapper = mapper;
        this.dispatchers = new EnumMap<>(OrchActionType.class);
        for (OrchActionDispatcher d : dispatcherList) {
            this.dispatchers.put(d.type(), d);
        }
        this.rulePause = rulePause;
        // FLOW 占位由 config 注册 UnsupportedFlowActionDispatcher（禁止本包访问 OrchActionType.FLOW）
    }

    @Transactional
    public void handleRunCreated(String runId) {
        OrchRunDO run = repo.lockRun(runId);
        if (run == null) {
            log.warn("orch-dispatcher: run 不存在 {}", runId);
            return;
        }
        if (!OrchApi.RunStatus.PENDING.name().equals(run.getStatus())) {
            return; // 幂等
        }

        OrchWorkItemDO wi = repo.findWorkItem(run.getWorkItemId());
        if (wi == null) {
            failMandate(run, "work_item_missing");
            return;
        }

        // serial：前面还有非终态 Run → 等待前一个结束再派
        if (repo.countNonTerminalBefore(run.getWorkItemId(), run.getCreateAt()) > 0) {
            return;
        }

        OrchMandateDO mandate = repo.findLatestMandate(wi.getId());
        OrchRuleVersionDO version = repo.findVersion(run.getRuleVersionId());
        if (!revalidateMandate(mandate, version, run.getWorkspaceId())) {
            revokeAndFail(run, wi, mandate, "MANDATE_INVALID");
            return;
        }

        Map<String, Object> action = firstDispatchableAction(version);
        if (action == null) {
            failMandate(run, "no_action");
            return;
        }
        OrchActionType actionType = OrchActionType.valueOf(
                String.valueOf(action.getOrDefault("type", "agent")).toUpperCase(Locale.ROOT));

        OrchEventDO event = repo.findEvent(run.getEventId());
        Map<String, String> attrs = readStringMap(event == null ? null : event.getAttrsJson());
        Map<String, Object> body = readObjectMap(event == null ? null : event.getBodyJson());
        if (run.getInputJson() != null) {
            Map<String, Object> input = readObjectMap(run.getInputJson());
            Object b = input.get("body");
            if (b instanceof Map<?, ?> m) {
                body = castObjectMap(m);
            }
            Object a = input.get("attrs");
            if (a instanceof Map<?, ?> m) {
                attrs = castStringMap(m);
            }
        }

        OrchDispatchCommand command = new OrchDispatchCommand(
                run.getId(), wi.getId(), run.getRuleVersionId(), actionType, action, attrs, body);

        Principal sa = loadSa(mandate.getServiceAccountId(), run.getWorkspaceId());
        principals.runAs(sa, () -> {
            OrchActionDispatcher dispatcher = dispatchers.get(actionType);
            if (dispatcher == null) {
                throw new IllegalStateException("无派工器: " + actionType);
            }
            dispatcher.dispatch(command);
            return null;
        });

        run = repo.lockRun(runId);
        if (run == null || !OrchApi.RunStatus.PENDING.name().equals(run.getStatus())) {
            return;
        }
        // AgentActionDispatcher 可能已写 thread/turn
        OrchRunDO latest = repo.findRun(runId);
        run.setThreadId(latest.getThreadId());
        run.setCurrentTurnId(latest.getCurrentTurnId());
        run.setStatus(OrchApi.RunStatus.DISPATCHED.name());
        run.setUpdateAt(LocalDateTime.now());
        repo.updateRun(run);
        if (wi.getThreadId() == null && latest.getThreadId() != null) {
            wi.setThreadId(latest.getThreadId());
            wi.setUpdateAt(LocalDateTime.now());
            repo.updateWorkItem(wi);
        }
        repo.appendFact(runId, "dispatched", json(Map.of(
                "actionType", actionType.name(),
                "threadId", latest.getThreadId() == null ? "" : latest.getThreadId(),
                "turnId", latest.getCurrentTurnId() == null ? "" : latest.getCurrentTurnId())));
    }

    private boolean revalidateMandate(OrchMandateDO mandate, OrchRuleVersionDO version, String workspaceId) {
        if (mandate == null || !"ACTIVE".equals(mandate.getStatus())) {
            return false;
        }
        if (version == null || "RETIRED".equals(version.getStage())) {
            return false;
        }
        try {
            loadSa(mandate.getServiceAccountId(), workspaceId);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void revokeAndFail(OrchRunDO run, OrchWorkItemDO wi, OrchMandateDO mandate, String code) {
        if (mandate != null && "ACTIVE".equals(mandate.getStatus())) {
            mandate.setStatus("REVOKED");
            mandate.setRevokedReason(code);
            mandate.setRevokedAt(LocalDateTime.now());
            repo.updateMandate(mandate);
        }
        run.setStatus(OrchApi.RunStatus.FAILED.name());
        run.setErrorCode(code);
        run.setErrorMessage("mandate invalid");
        run.setEndedAt(LocalDateTime.now());
        run.setUpdateAt(LocalDateTime.now());
        repo.updateRun(run);
        // ORC-07 自动暂停：Run FAILED 计入规则连续失败
        rulePause.recordRunOutcome(run.getRuleVersionId(), OrchApi.RunStatus.FAILED.name());
        wi.setStatus("STOPPED");
        wi.setActiveRunId(null);
        wi.setUpdateAt(LocalDateTime.now());
        repo.updateWorkItem(wi);
        repo.appendFact(run.getId(), "mandate_invalid", json(Map.of("code", code)));
    }

    private void failMandate(OrchRunDO run, String code) {
        run.setStatus(OrchApi.RunStatus.FAILED.name());
        run.setErrorCode(code);
        run.setEndedAt(LocalDateTime.now());
        run.setUpdateAt(LocalDateTime.now());
        repo.updateRun(run);
        // ORC-07 自动暂停：Run FAILED 计入规则连续失败
        rulePause.recordRunOutcome(run.getRuleVersionId(), OrchApi.RunStatus.FAILED.name());
        repo.appendFact(run.getId(), "dispatch_failed", json(Map.of("code", code)));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> firstDispatchableAction(OrchRuleVersionDO version) {
        if (version == null || version.getActionsJson() == null) {
            return null;
        }
        try {
            List<Map<String, Object>> actions = mapper.readValue(version.getActionsJson(), new TypeReference<>() {});
            for (Map<String, Object> a : actions) {
                String type = String.valueOf(a.getOrDefault("type", "")).toLowerCase(Locale.ROOT);
                if ("admission".equals(type)) {
                    continue; // 决定阶段处理；若仅有 admission，下面会落到它
                }
                if ("agent".equals(type) || "flow".equals(type)) {
                    return a;
                }
            }
            // 仅 admission
            if (!actions.isEmpty()) {
                return actions.get(0);
            }
        } catch (Exception e) {
            log.warn("解析 actions_json 失败: {}", e.toString());
        }
        return null;
    }

    private Principal loadSa(String serviceAccountId, String workspaceId) {
        try {
            return principalLoader.load(Long.parseLong(serviceAccountId), workspaceId);
        } catch (NumberFormatException e) {
            throw new BizException(com.tioka.framework.error.ErrorCode.BAD_REQUEST, "service_account_id 非法");
        }
    }

    private Map<String, String> readStringMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> raw = mapper.readValue(json, new TypeReference<>() {});
            return castStringMap(raw);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private Map<String, Object> readObjectMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return mapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static Map<String, String> castStringMap(Map<?, ?> m) {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        m.forEach((k, v) -> {
            if (k != null && v != null) {
                out.put(String.valueOf(k), String.valueOf(v));
            }
        });
        return out;
    }

    private static Map<String, Object> castObjectMap(Map<?, ?> m) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        m.forEach((k, v) -> {
            if (k != null) {
                out.put(String.valueOf(k), v);
            }
        });
        return out;
    }

    private String json(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            return "{}";
        }
    }
}
