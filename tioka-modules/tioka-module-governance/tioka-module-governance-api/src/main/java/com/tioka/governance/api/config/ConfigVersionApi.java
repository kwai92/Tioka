package com.tioka.governance.api.config;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * 统一配置版本框架契约（ADR-041 §2 / GOV-12）。
 * <p>本接口为契约先行；实现与生命周期 IT 在后续工作项完成。
 */
public interface ConfigVersionApi {

    /** 保存草稿；revision 冲突时抛业务冲突（HTTP 409）。 */
    ConfigDraft saveDraft(SaveDraftCommand command);

    /** 发布前校验。 */
    ValidationReport validate(ObjectRef ref);

    /** 发布前影响分析。 */
    ImpactReport analyzeImpact(ObjectRef ref);

    /** 与指定版本（或当前生效）的差异。 */
    DiffReport diff(ObjectRef ref, Integer againstVersionNo);

    /** 发布为不可变版本。 */
    ConfigVersion publish(PublishCommand command);

    /** 回滚到已有版本（不产生新版本号）。 */
    ConfigVersion rollback(RollbackCommand command);

    /** 导出已发布版本（不含密钥值）。 */
    ExportBundle exportPublished(List<ObjectRef> refs);

    /** 导入后生成草稿，不自动发布。 */
    List<ConfigDraft> importAsDrafts(ExportBundle bundle);

    record ObjectRef(String objectType, String objectId) {}

    record SaveDraftCommand(ObjectRef ref, JsonNode spec, long expectedRevision) {}

    record PublishCommand(ObjectRef ref, String note) {}

    record RollbackCommand(ObjectRef ref, int targetVersionNo, String reason) {}

    record ConfigDraft(
            ObjectRef ref,
            JsonNode spec,
            long revision,
            Integer basedOnVersionNo
    ) {}

    record ConfigVersion(
            String versionId,
            ObjectRef ref,
            int versionNo,
            JsonNode spec,
            String contentHash,
            String status
    ) {}

    record ValidationIssue(String code, String severity, String message, String path) {}

    record ValidationReport(boolean ok, List<ValidationIssue> issues) {}

    record DiffReport(ObjectRef ref, JsonNode before, JsonNode after) {}

    record ImpactRef(String kind, String id, String label) {}

    record ImpactReport(
            ObjectRef ref,
            DiffReport diff,
            List<ImpactRef> dependents,
            long callsLast7d,
            double costLast7d
    ) {}

    record ExportBundle(String formatVersion, List<ConfigVersion> versions) {}
}
