package com.tioka.governance.application.policy;

import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.id.Ids;
import com.tioka.governance.api.policy.PolicyAdminApi;
import com.tioka.governance.api.policy.PolicyDecision;
import com.tioka.governance.api.policy.PolicyMode;
import com.tioka.governance.infrastructure.persistence.GovPolicyDO;
import com.tioka.governance.infrastructure.policy.GovPolicyRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * GOV-07 策略存储（M3-05 §1.1）：gov_policy 读写；策略按（空间、来源）分组带版本号；
 * 授权书来源（source = charter:{agentVersionId}）只能由 TRU-02 编译路径写入；
 * 导入/编译出 permit 的策略保存时拒绝（策略只能收紧，M3-05 §1.3）。
 *
 * <p>permit 校验用词法判断（本类不得依赖 cedar-java，ArchUnit cedarOnlyInGovernance 只豁免
 * {@link PolicyEvaluator}）；求值侧 {@link PolicyEvaluator} 再次按 effect 过滤兜底。
 */
public class PolicyStore {

    /** 一条语句以 permit 开头即拒绝（forbid 组之外不允许放行）。 */
    private static final Pattern PERMIT_STATEMENT = Pattern.compile("(?m)^\\s*permit\\b");
    public static final String SOURCE_SYSTEM = "system";
    public static final String SOURCE_IMPORT = "import";
    public static final String SOURCE_CHARTER_PREFIX = "charter:";
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_DISABLED = "DISABLED";

    private final GovPolicyRepository repository;
    private final String importDefaultMode;

    public PolicyStore(GovPolicyRepository repository, String importDefaultMode) {
        this.repository = repository;
        this.importDefaultMode = importDefaultMode == null || importDefaultMode.isBlank()
                ? PolicyMode.DRYRUN.name() : importDefaultMode;
    }

    /** 求值输入：系统级 + 当前空间 + charter:{agentVersionId} 的 ACTIVE 策略。 */
    public List<GovPolicyDO> loadActiveForEvaluation(String workspaceId) {
        return repository.listActiveForWorkspace(workspaceId);
    }

    /** 导入一条空间 forbid 策略；permit 一律拒绝；@effect 注解读取（无注解按 DENY）。 */
    public GovPolicyDO importPolicy(PolicyAdminApi.ImportCommand cmd) {
        rejectPermit(cmd.cedarText());
        PolicyMode mode = cmd.mode() == null ? PolicyMode.valueOf(importDefaultMode) : cmd.mode();
        GovPolicyDO row = baseRow(cmd.workspaceId(), SOURCE_IMPORT, policyIdOf(cmd.cedarText()),
                cmd.cedarText(), mode);
        row.setEffect(effectOf(cmd.cedarText()).name());
        row.setCreatedBy(cmd.createdBy() == null || cmd.createdBy().isBlank()
                ? "system" : cmd.createdBy());
        row.setVersion(repository.maxVersion(cmd.workspaceId(), SOURCE_IMPORT) + 1);
        repository.insert(row);
        return row;
    }

    /**
     * TRU-02 编译路径：整组替换（旧版本停用保留、新版本 ACTIVE、重复调用幂等）。
     * 只允许 source 以 {@code charter:} 开头；每条文本必须 forbid。
     */
    public int replaceCharterPolicies(String workspaceId, String agentVersionId,
                                      List<CedarPolicySpec> specs, String createdBy) {
        String source = SOURCE_CHARTER_PREFIX + agentVersionId;
        for (CedarPolicySpec spec : specs) {
            rejectPermit(spec.cedarText());
        }
        repository.disableBySource(workspaceId, source);
        int version = repository.maxVersion(workspaceId, source) + 1;
        for (CedarPolicySpec spec : specs) {
            GovPolicyDO row = baseRow(workspaceId, source, spec.policyId(), spec.cedarText(),
                    PolicyMode.ENFORCE);
            row.setEffect(spec.effect().name());
            row.setVersion(version);
            row.setCreatedBy(createdBy == null || createdBy.isBlank() ? "system" : createdBy);
            repository.insert(row);
        }
        return version;
    }

    /** 版本归档：整组停用（TRU-02 编译路径专用）。 */
    public int deactivateCharterPolicies(String workspaceId, String agentVersionId) {
        return repository.disableBySource(workspaceId, SOURCE_CHARTER_PREFIX + agentVersionId);
    }

    public GovPolicyDO setMode(String policyRowId, PolicyMode mode) {
        if (repository.updateMode(policyRowId, mode.name()) == 0) {
            throw new BizException(ErrorCode.NOT_FOUND, "策略不存在: " + policyRowId);
        }
        return require(policyRowId);
    }

    public GovPolicyDO disable(String policyRowId) {
        if (repository.disable(policyRowId) == 0) {
            throw new BizException(ErrorCode.NOT_FOUND, "策略不存在: " + policyRowId);
        }
        return require(policyRowId);
    }

    public List<PolicyAdminApi.PolicyView> list(String workspaceId, String source) {
        List<GovPolicyDO> rows = source == null || source.isBlank()
                ? repository.listBySource(workspaceId, SOURCE_IMPORT)
                : repository.listBySource(workspaceId, source);
        if (source != null && !source.isBlank()) {
            return rows.stream().map(PolicyStore::toView).toList();
        }
        // source 未指定：返回该空间全部来源
        return repository.listActiveForWorkspace(workspaceId).stream()
                .filter(r -> r.getWorkspaceId() != null)
                .map(PolicyStore::toView)
                .toList();
    }

    public PolicyAdminApi.PolicyView detail(String policyRowId) {
        return toView(require(policyRowId));
    }

    /** 求值侧分组版本号：取该空间 ACTIVE 策略中最大的（空间、来源）版本（审计可还原）。 */
    public int currentSetVersion(String workspaceId) {
        return repository.listActiveForWorkspace(workspaceId).stream()
                .mapToInt(GovPolicyDO::getVersion)
                .max()
                .orElse(0);
    }

    private GovPolicyDO require(String id) {
        GovPolicyDO row = repository.findById(id);
        if (row == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "策略不存在: " + id);
        }
        return row;
    }

    private static void rejectPermit(String cedarText) {
        if (cedarText == null || cedarText.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "Cedar 文本必填");
        }
        if (PERMIT_STATEMENT.matcher(cedarText).find()) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "策略只能收紧：不允许导入/编译 permit 策略（M3-05 §1.3）");
        }
    }

    /** 取 Cedar 文本 @id 注解，缺失时按行内容派生占位（唯一索引仍保护重复）。 */
    static String policyIdOf(String cedarText) {
        java.util.regex.Matcher m = Pattern.compile("@id\\(\"([^\"]+)\"\\)").matcher(cedarText);
        return m.find() ? m.group(1) : "import:" + Integer.toHexString(cedarText.hashCode());
    }

    /** @effect 注解词法读取；无注解按 DENY（M3-05 §1.3）。 */
    static PolicyDecision effectOf(String cedarText) {
        java.util.regex.Matcher m = Pattern.compile("@effect\\(\"(\\w+)\"\\)").matcher(cedarText);
        if (m.find()) {
            try {
                return PolicyDecision.valueOf(m.group(1));
            } catch (RuntimeException ignored) {
                return PolicyDecision.DENY;
            }
        }
        return PolicyDecision.DENY;
    }

    private static GovPolicyDO baseRow(String workspaceId, String source, String policyId,
                                       String cedarText, PolicyMode mode) {
        GovPolicyDO row = new GovPolicyDO();
        row.setId(Ids.newId("pol"));
        row.setWorkspaceId(workspaceId);
        row.setSource(source);
        row.setPolicyId(policyId);
        row.setCedarText(cedarText);
        row.setMode(mode.name());
        row.setEffect(PolicyDecision.DENY.name());
        row.setStatus(STATUS_ACTIVE);
        row.setCreateAt(LocalDateTime.now());
        row.setUpdateAt(LocalDateTime.now());
        return row;
    }

    private static PolicyAdminApi.PolicyView toView(GovPolicyDO row) {
        PolicyDecision effect;
        try {
            effect = PolicyDecision.valueOf(row.getEffect());
        } catch (RuntimeException e) {
            effect = PolicyDecision.DENY;
        }
        return new PolicyAdminApi.PolicyView(row.getId(), row.getWorkspaceId(),
                row.getVersion() == null ? 0 : row.getVersion(), row.getSource(), row.getPolicyId(),
                row.getCedarText(), PolicyMode.valueOf(row.getMode()), effect, row.getStatus());
    }

    /** 编译产物：一条 forbid 语句 + 生效组。 */
    public record CedarPolicySpec(String policyId, String cedarText, PolicyDecision effect) {}

    /** 兼容别名：分层读取统一走 repository。 */
    public Map<String, List<GovPolicyDO>> groupBySource(String workspaceId) {
        return repository.listActiveForWorkspace(workspaceId).stream()
                .collect(Collectors.groupingBy(GovPolicyDO::getSource));
    }
}
