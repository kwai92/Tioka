package com.tioka.ontology.application.def;

import com.tioka.ontology.api.def.ModelDefinition;
import com.tioka.ontology.api.def.ModelDefinition.AttrDef;
import com.tioka.ontology.api.def.ModelDefinition.AttrGroup;
import com.tioka.ontology.api.def.ModelDefinition.ObjectType;
import com.tioka.ontology.api.def.ModelDefinition.StatementParam;
import com.tioka.ontology.api.def.ModelDefinition.StatementType;
import com.tioka.ontology.api.def.ModelDefinition.Vocabulary;
import com.tioka.ontology.api.def.PathCatalogEntry;
import com.tioka.ontology.api.def.PathValueKind;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 由对象类型、属性组、陈述类型生成路径目录（M3-03 §6）。
 * 集合元素用 {@code [*]}；陈述按 typeKey 选择器展开。
 */
public final class PathCatalogBuilder {

    private PathCatalogBuilder() {
    }

    public static List<PathCatalogEntry> build(ModelDefinition def) {
        Map<String, PathCatalogEntry> byPath = new LinkedHashMap<>();
        Map<String, Vocabulary> vocabs = def.vocabularies();

        for (ObjectType ot : def.objectTypes()) {
            String root = "m." + ot.collectionKey() + "[*]";
            for (AttrGroup group : ot.groups()) {
                String groupSeg = group.groupKey().toLowerCase(Locale.ROOT);
                String groupBase = root + "." + groupSeg;
                for (AttrDef attr : group.attrs()) {
                    walkAttr(groupBase, attr, vocabs, byPath);
                }
            }
        }

        for (StatementType st : def.statementTypes()) {
            String base = "m.statements[" + st.typeKey() + "]";
            for (StatementParam param : st.params()) {
                if (isStructural(param.kind())) {
                    continue;
                }
                String path = base + "." + param.name();
                byPath.put(path, new PathCatalogEntry(
                        path,
                        param.kind(),
                        enumValues(param.enumVocabulary(), vocabs),
                        null,
                        param.topics()));
            }
        }
        return List.copyOf(byPath.values());
    }

    private static void walkAttr(
            String parentPath,
            AttrDef attr,
            Map<String, Vocabulary> vocabs,
            Map<String, PathCatalogEntry> byPath) {
        if (attr.kind() == PathValueKind.ARRAY) {
            String arrayPath = parentPath + "." + attr.name() + "[*]";
            for (AttrDef child : attr.children()) {
                walkAttr(arrayPath, child, vocabs, byPath);
            }
            return;
        }
        if (attr.kind() == PathValueKind.OBJECT) {
            String objectPath = parentPath + "." + attr.name();
            for (AttrDef child : attr.children()) {
                walkAttr(objectPath, child, vocabs, byPath);
            }
            return;
        }
        String path = parentPath + "." + attr.name();
        byPath.put(path, new PathCatalogEntry(
                path,
                attr.kind(),
                enumValues(attr.enumVocabulary(), vocabs),
                attr.unit(),
                attr.topics()));
    }

    private static List<String> enumValues(String vocabKey, Map<String, Vocabulary> vocabs) {
        if (vocabKey == null || vocabKey.isBlank()) {
            return List.of();
        }
        Vocabulary v = vocabs.get(vocabKey);
        return v == null ? List.of() : List.copyOf(v.values());
    }

    private static boolean isStructural(PathValueKind kind) {
        return kind == PathValueKind.OBJECT || kind == PathValueKind.ARRAY;
    }
}
