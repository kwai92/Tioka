package com.tioka.eval.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.eval.api.dataset.EvalCaseView;
import com.tioka.eval.api.dataset.EvalDatasetApi;
import com.tioka.eval.api.dataset.EvalDatasetView;
import com.tioka.eval.api.EvalApi;
import com.tioka.eval.infrastructure.EvalDatasetRepository;
import com.tioka.eval.infrastructure.persistence.EvalCaseDO;
import com.tioka.eval.infrastructure.persistence.EvalDatasetDO;
import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.id.Ids;
import com.tioka.framework.security.Permissions;
import com.tioka.framework.security.PrincipalHolder;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * EVO-02：数据集与用例版本管理（M3-06 §4.2 / ADR-031）。
 * createDataset 落 latest_version=0；importCases 整批落 latest_version+1 的新版本（不可变历史），
 * source 缺省 IMPORT（ONLINE 由轨迹回流写入，接口层不收）。
 *
 * <p><b>C-18（EVO-07 / ADR-068）</b>：
 * <ul>
 *   <li>分层：createDataset 收 tier（五层枚举校验，缺省 DEV；ADR-068 第 1 节）。
 *       SEALED 层创建不设额外门槛——密封集保护在读取侧，由权限点
 *       {@code eval:sealed:read} 管逐条内容可见性（见 get/listCases 脱敏）。</li>
 *   <li>用例卡：importCases 落 unit_key / label_status / must_pass
 *       （ADR-068 第 2 节；label_status=STALE/DISPUTED 允许导入，不参与门禁统计——
 *       统计口径见 BatchEvalService metrics.excludedCount）。</li>
 *   <li>UnitSplit：同一 workspace 内 unit_key 已存在于 tier 不同的数据集（含历史版本）
 *       → 拒绝导入（CONFLICT；ADR-068 L63「一个材料单元派生的所有用例必须在同一层」；
 *       同层不同数据集允许，ADR-068 只禁跨层）。unit_key_path 未声明时 unit_key 可空，
 *       悬空不校验。</li>
 *   <li>密封集隔离（ADR-068 L47）：tier=SEALED 的数据集，无 {@code eval:sealed:read}
 *       权限者 get/listCases 逐条内容脱敏（input/expected 置 null，选择「脱敏」而非
 *       「拒绝」口径：与 getRun 汇总可见、逐条隐藏一致，元数据照常返回）。</li>
 * </ul>
 */
public class DatasetService implements EvalDatasetApi {

    /** ADR-068 第 1 节五层。 */
    public static final List<String> TIERS = List.of("SMOKE", "DEV", "SEALED", "REGRESSION", "SPECIAL");
    public static final String TIER_DEFAULT = "DEV";
    public static final String TIER_SEALED = "SEALED";
    /** ADR-068 第 2 节 label_status 三态。 */
    public static final List<String> LABEL_STATUSES = List.of("VALID", "STALE", "DISPUTED");
    public static final String LABEL_STATUS_DEFAULT = "VALID";

    private final EvalDatasetRepository repo;
    private final ObjectMapper mapper;
    private final PrincipalHolder principals;

    public DatasetService(EvalDatasetRepository repo, ObjectMapper mapper, PrincipalHolder principals) {
        this.repo = repo;
        this.mapper = mapper;
        this.principals = principals;
    }

    @Transactional
    public EvalDatasetView createDataset(EvalApi.CreateDatasetCmd cmd) {
        if (cmd == null || cmd.name() == null || cmd.name().isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "数据集名称不能为空");
        }
        String tier = cmd.tier() == null || cmd.tier().isBlank() ? TIER_DEFAULT : cmd.tier().trim();
        if (!TIERS.contains(tier)) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "tier 仅支持 SMOKE/DEV/SEALED/REGRESSION/SPECIAL: " + tier);
        }
        EvalDatasetDO row = new EvalDatasetDO();
        row.setId(Ids.newId("ds"));
        row.setWorkspaceId(null); // MetaObjectHandler 按当前 Principal 填充
        row.setName(cmd.name().trim());
        row.setSchemaJson(cmd.schemaJson() == null ? "{}" : cmd.schemaJson());
        row.setLatestVersion(0);
        row.setTier(tier);
        row.setUnitKeyPath(cmd.unitKeyPath());
        repo.insertDataset(row);
        return new EvalDatasetView(row.getId(), row.getWorkspaceId(), row.getName(),
                row.getLatestVersion(), row.getTier(), row.getUnitKeyPath());
    }

    @Transactional
    public EvalApi.DatasetVersionView importCases(String datasetId, List<EvalApi.EvalCase> cases) {
        EvalDatasetDO dataset = repo.findDataset(datasetId);
        if (dataset == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "数据集不存在");
        }
        if (cases == null || cases.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "用例列表不能为空");
        }
        int version = (dataset.getLatestVersion() == null ? 0 : dataset.getLatestVersion()) + 1;
        for (EvalApi.EvalCase c : cases) {
            if (c == null || c.input() == null || c.input().isNull()) {
                throw new BizException(ErrorCode.BAD_REQUEST, "用例 input 不能为空");
            }
            String labelStatus = c.labelStatus() == null || c.labelStatus().isBlank()
                    ? LABEL_STATUS_DEFAULT : c.labelStatus().trim();
            if (!LABEL_STATUSES.contains(labelStatus)) {
                throw new BizException(ErrorCode.BAD_REQUEST,
                        "labelStatus 仅支持 VALID/STALE/DISPUTED: " + labelStatus);
            }
            EvalCaseDO row = new EvalCaseDO();
            row.setId(Ids.newId("case"));
            row.setDatasetId(datasetId);
            row.setVersion(version);
            row.setInputJson(write(c.input()));
            row.setExpectedJson(c.expected() == null ? null : write(c.expected()));
            String source = c.source() == null || c.source().isBlank() ? "IMPORT" : c.source().trim();
            if (!List.of("MANUAL", "IMPORT").contains(source)) {
                throw new BizException(ErrorCode.BAD_REQUEST, "source 仅支持 MANUAL / IMPORT: " + source);
            }
            row.setSource(source);
            row.setUnitKey(c.unitKey() == null || c.unitKey().isBlank() ? null : c.unitKey().trim());
            row.setLabelStatus(labelStatus);
            row.setMustPass(c.mustPass() != null && c.mustPass());
            repo.insertCase(row);
        }
        // UnitSplit（ADR-068 L63）：一个材料单元只能属于一层；跨层导入拒绝
        assertUnitsInSingleTier(dataset, cases);
        repo.bumpLatestVersion(datasetId, version);
        return new EvalApi.DatasetVersionView(datasetId, version, cases.size());
    }

    /**
     * 同一 workspace 内，本批任一 unit_key 已落在 tier 不同的数据集（含历史版本）→ 拒绝。
     * 同层不同数据集允许（ADR-068 只禁跨层）；本数据集自身行 tier 相同天然放行。
     */
    private void assertUnitsInSingleTier(EvalDatasetDO dataset, List<EvalApi.EvalCase> cases) {
        Set<String> unitKeys = new HashSet<>();
        for (EvalApi.EvalCase c : cases) {
            if (c != null && c.unitKey() != null && !c.unitKey().isBlank()) {
                unitKeys.add(c.unitKey().trim());
            }
        }
        if (unitKeys.isEmpty()) {
            return;
        }
        String workspaceId = dataset.getWorkspaceId() != null ? dataset.getWorkspaceId()
                : principals.current().workspaceId();
        if (workspaceId == null || workspaceId.isBlank()) {
            return;
        }
        String datasetTier = dataset.getTier() == null ? TIER_DEFAULT : dataset.getTier();
        for (Map<String, Object> row : repo.selectUnitTiers(workspaceId, unitKeys)) {
            String unitKey = String.valueOf(row.get("unitKey"));
            String tier = String.valueOf(row.get("tier"));
            if (!tier.equals(datasetTier)) {
                throw new BizException(ErrorCode.CONFLICT,
                        "材料单元跨层冲突（ADR-068 L63 一个材料单元只能属于一层）: unit_key="
                                + unitKey + " 已存在于 " + tier + " 层，不能导入到 " + datasetTier + " 层");
            }
        }
    }

    @Override
    public Optional<EvalDatasetView> get(String datasetId) {
        EvalDatasetDO d = repo.findDataset(datasetId);
        if (d == null) {
            return Optional.empty();
        }
        return Optional.of(new EvalDatasetView(d.getId(), d.getWorkspaceId(), d.getName(),
                d.getLatestVersion() == null ? 0 : d.getLatestVersion(), d.getTier(), d.getUnitKeyPath()));
    }

    @Override
    public List<EvalCaseView> listCases(String datasetId, int version) {
        EvalDatasetDO dataset = repo.findDataset(datasetId);
        if (dataset == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "数据集不存在");
        }
        // 密封集隔离（ADR-068 L47）：SEALED 层逐条内容仅 eval:sealed:read 可见；
        // 无权者脱敏（input/expected 置 null），元数据照常——与 getRun「汇总可见、逐条隐藏」一致
        boolean redact = isSealedLocked(dataset);
        List<EvalCaseView> out = new ArrayList<>();
        for (EvalCaseDO c : repo.listCases(datasetId, version)) {
            out.add(new EvalCaseView(c.getId(), c.getDatasetId(), c.getVersion(),
                    redact ? null : read(c.getInputJson()),
                    redact ? null : read(c.getExpectedJson()),
                    c.getSource(), c.getUnitKey(), c.getLabelStatus(),
                    c.getMustPass() != null && c.getMustPass()));
        }
        return out;
    }

    /** 密封集锁定判定：SEALED 层且当前主体无 eval:sealed:read。 */
    private boolean isSealedLocked(EvalDatasetDO dataset) {
        if (!TIER_SEALED.equals(dataset.getTier())) {
            return false;
        }
        try {
            return !principals.current().hasPermission(Permissions.EVAL_SEALED_READ);
        } catch (BizException e) {
            // 无身份上下文（纯后台路径）按无权限处理，保守脱敏
            return true;
        }
    }

    private String write(JsonNode node) {
        try {
            return mapper.writeValueAsString(node);
        } catch (Exception e) {
            throw new BizException(ErrorCode.BAD_REQUEST, "用例 JSON 无法序列化");
        }
    }

    private JsonNode read(String json) {
        try {
            return mapper.readTree(json == null ? "null" : json);
        } catch (Exception e) {
            return mapper.nullNode();
        }
    }

    /** 供 BatchEvalService 复用：startRun 校验数据集存在性。 */
    public EvalDatasetDO findDataset(String datasetId) {
        return repo.findDataset(datasetId);
    }

    /** 供 BatchEvalService 复用：读取运行版本用例（datasetVersion=null → latest）。 */
    List<EvalCaseDO> casesForRun(String datasetId, Integer datasetVersion) {
        EvalDatasetDO dataset = repo.findDataset(datasetId);
        if (dataset == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "数据集不存在");
        }
        int version = datasetVersion == null
                ? (dataset.getLatestVersion() == null ? 0 : dataset.getLatestVersion())
                : datasetVersion;
        return repo.listCases(datasetId, version);
    }

    /** 供 BatchEvalService 复用：按行 ID 解析评估器实例。 */
    Map<String, com.tioka.eval.infrastructure.persistence.EvalEvaluatorDO> evaluatorsById(List<String> ids) {
        java.util.LinkedHashMap<String, com.tioka.eval.infrastructure.persistence.EvalEvaluatorDO> out =
                new java.util.LinkedHashMap<>();
        for (com.tioka.eval.infrastructure.persistence.EvalEvaluatorDO row : repo.findEvaluators(ids)) {
            out.put(row.getId(), row);
        }
        return out;
    }
}
