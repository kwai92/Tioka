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
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * EVO-02：数据集与用例版本管理（M3-06 §4.2 / ADR-031）。
 * createDataset 落 latest_version=0；importCases 整批落 latest_version+1 的新版本（不可变历史），
 * source 缺省 IMPORT（ONLINE 由轨迹回流写入，接口层不收）。
 */
public class DatasetService implements EvalDatasetApi {

    private final EvalDatasetRepository repo;
    private final ObjectMapper mapper;

    public DatasetService(EvalDatasetRepository repo, ObjectMapper mapper) {
        this.repo = repo;
        this.mapper = mapper;
    }

    @Transactional
    public EvalDatasetView createDataset(EvalApi.CreateDatasetCmd cmd) {
        if (cmd == null || cmd.name() == null || cmd.name().isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "数据集名称不能为空");
        }
        EvalDatasetDO row = new EvalDatasetDO();
        row.setId(Ids.newId("ds"));
        row.setWorkspaceId(null); // MetaObjectHandler 按当前 Principal 填充
        row.setName(cmd.name().trim());
        row.setSchemaJson(cmd.schemaJson() == null ? "{}" : cmd.schemaJson());
        row.setLatestVersion(0);
        repo.insertDataset(row);
        return new EvalDatasetView(row.getId(), row.getWorkspaceId(), row.getName(), row.getLatestVersion());
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
            repo.insertCase(row);
        }
        repo.bumpLatestVersion(datasetId, version);
        return new EvalApi.DatasetVersionView(datasetId, version, cases.size());
    }

    @Override
    public Optional<EvalDatasetView> get(String datasetId) {
        EvalDatasetDO d = repo.findDataset(datasetId);
        if (d == null) {
            return Optional.empty();
        }
        return Optional.of(new EvalDatasetView(d.getId(), d.getWorkspaceId(), d.getName(),
                d.getLatestVersion() == null ? 0 : d.getLatestVersion()));
    }

    @Override
    public List<EvalCaseView> listCases(String datasetId, int version) {
        List<EvalCaseView> out = new ArrayList<>();
        for (EvalCaseDO c : repo.listCases(datasetId, version)) {
            out.add(new EvalCaseView(c.getId(), c.getDatasetId(), c.getVersion(),
                    read(c.getInputJson()), read(c.getExpectedJson()), c.getSource()));
        }
        return out;
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
