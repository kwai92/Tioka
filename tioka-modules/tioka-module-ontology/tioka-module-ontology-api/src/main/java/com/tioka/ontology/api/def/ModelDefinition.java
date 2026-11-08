package com.tioka.ontology.api.def;

import java.util.List;
import java.util.Map;

/**
 * 模型定义资产（ADR-036）。字段与 ADR 对齐；子 record 一并放在本文件。
 */
public record ModelDefinition(
        int schemaVersion,
        String defKey,
        String entityType,
        BusinessKeyRule businessKey,
        List<ObjectType> objectTypes,
        List<StatementType> statementTypes,
        List<Topic> topics,
        Map<String, Vocabulary> vocabularies,
        ModificationVocabulary modifications,
        List<String> validatorIds,
        List<FieldView> baseViews,
        /** 领域包自带规则引用的路径（发布校验第 3 条）；ONT-04 前由调用方显式传入。 */
        List<String> packRulePaths
) {
    public ModelDefinition {
        if (objectTypes == null) {
            objectTypes = List.of();
        }
        if (statementTypes == null) {
            statementTypes = List.of();
        }
        if (topics == null) {
            topics = List.of();
        }
        if (vocabularies == null) {
            vocabularies = Map.of();
        }
        if (modifications == null) {
            modifications = ModificationVocabulary.empty();
        }
        if (validatorIds == null) {
            validatorIds = List.of();
        }
        if (baseViews == null) {
            baseViews = List.of();
        }
        if (packRulePaths == null) {
            packRulePaths = List.of();
        }
    }

    /** 业务键规则：部件顺序拼接；缺某部件时用 fallback（如首份文件哈希）。 */
    public record BusinessKeyRule(List<String> parts, String fallback) {
        public BusinessKeyRule {
            if (parts == null) {
                parts = List.of();
            }
        }
    }

    /**
     * 对象类型。
     *
     * @param collectionKey 实例路径集合名，如 feeItems；缺省用 typeKey
     */
    public record ObjectType(
            String typeKey,
            String collectionKey,
            List<String> identityKeys,
            List<AttrGroup> groups,
            List<EvidenceRole> evidenceRoles,
            List<Ref> refs
    ) {
        public ObjectType {
            if (collectionKey == null || collectionKey.isBlank()) {
                collectionKey = typeKey;
            }
            if (identityKeys == null) {
                identityKeys = List.of();
            }
            if (groups == null) {
                groups = List.of();
            }
            if (evidenceRoles == null) {
                evidenceRoles = List.of();
            }
            if (refs == null) {
                refs = List.of();
            }
        }
    }

    public record AttrGroup(String groupKey, List<AttrDef> attrs) {
        public AttrGroup {
            if (attrs == null) {
                attrs = List.of();
            }
        }
    }

    /**
     * 属性定义。OBJECT/ARRAY 通过 {@code children} 展开；叶子写入路径目录。
     */
    public record AttrDef(
            String name,
            PathValueKind kind,
            String unit,
            String enumVocabulary,
            List<String> topics,
            List<AttrDef> children
    ) {
        public AttrDef {
            if (topics == null) {
                topics = List.of();
            }
            if (children == null) {
                children = List.of();
            }
            if (kind == null) {
                kind = PathValueKind.TEXT;
            }
        }

        public static AttrDef leaf(String name, PathValueKind kind) {
            return new AttrDef(name, kind, null, null, List.of(), List.of());
        }

        public static AttrDef leaf(String name, PathValueKind kind, String unit, String enumVocabulary, List<String> topics) {
            return new AttrDef(name, kind, unit, enumVocabulary, topics, List.of());
        }
    }

    public record StatementType(String typeKey, List<StatementParam> params) {
        public StatementType {
            if (params == null) {
                params = List.of();
            }
        }
    }

    public record StatementParam(
            String name,
            PathValueKind kind,
            String enumVocabulary,
            List<String> topics
    ) {
        public StatementParam {
            if (topics == null) {
                topics = List.of();
            }
            if (kind == null) {
                kind = PathValueKind.TEXT;
            }
        }
    }

    public record Topic(
            String topicKey,
            List<String> expectedObjectTypes,
            List<String> expectedStatementTypes
    ) {
        public Topic {
            if (expectedObjectTypes == null) {
                expectedObjectTypes = List.of();
            }
            if (expectedStatementTypes == null) {
                expectedStatementTypes = List.of();
            }
        }
    }

    public record Vocabulary(String vocabKey, List<String> values) {
        public Vocabulary {
            if (values == null) {
                values = List.of();
            }
        }
    }

    /** 修改类型 → 变更操作列表。 */
    public record ModificationVocabulary(Map<String, List<String>> typeToOps) {
        public ModificationVocabulary {
            if (typeToOps == null) {
                typeToOps = Map.of();
            }
        }

        public static ModificationVocabulary empty() {
            return new ModificationVocabulary(Map.of());
        }
    }

    public record FieldView(String viewKey, List<FieldMapping> fields) {
        public FieldView {
            if (fields == null) {
                fields = List.of();
            }
        }
    }

    public record FieldMapping(String fieldKey, String path) {
    }

    public record Ref(String field, String targetTypeKey) {
    }
}
