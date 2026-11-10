package com.tioka.ontology.application.instance;

import com.tioka.ontology.infrastructure.persistence.OntDraftDO;

import java.util.ArrayList;

/** OntDraftDO ↔ ModelDraft 映射工具（JSON 解析由调用方 mapper 提供）。 */
final class OntDraftRepositoryAccess {

    private OntDraftRepositoryAccess() {
    }

    static ModelDraft toModel(OntDraftDO row, com.fasterxml.jackson.databind.ObjectMapper mapper) {
        return new ModelDraft(
                row.getInstanceKey(),
                row.getDefKey(),
                row.getDefVersion() == null ? 0 : row.getDefVersion(),
                row.getStoreKey(),
                row.getDocIds() == null ? java.util.List.of() : new ArrayList<>(row.getDocIds()),
                parse(row.getModelJson(), mapper),
                parse(row.getValidationJson(), mapper),
                row.getBuildRunId(),
                row.getBizDraftRef(),
                row.getRevision() == null ? 0 : row.getRevision());
    }

    private static com.fasterxml.jackson.databind.JsonNode parse(String json,
                                                                 com.fasterxml.jackson.databind.ObjectMapper mapper) {
        if (json == null || json.isBlank()) {
            return mapper.nullNode();
        }
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("ont_draft JSON 解析失败", e);
        }
    }
}
