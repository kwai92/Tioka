package com.tioka.ontology.application.def;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tioka.ontology.api.def.ModelDefinition;
import com.tioka.ontology.api.def.ModelDefinition.AttrDef;
import com.tioka.ontology.api.def.ModelDefinition.AttrGroup;
import com.tioka.ontology.api.def.ModelDefinition.ObjectType;
import com.tioka.ontology.api.def.ModelDefinition.StatementParam;
import com.tioka.ontology.api.def.ModelDefinition.StatementType;
import com.tioka.ontology.api.def.ModelDefinition.Vocabulary;
import com.tioka.ontology.api.def.PathValueKind;
import com.tioka.ontology.api.schema.SchemaDef;
import com.tioka.ontology.application.schema.SchemaRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 每个对象类型、陈述类型派生一份 Schema，发布到 sch_schema（经 {@link SchemaRegistry}），
 * {@code owner_pack} = 领域包。
 */
public class SchemaDeriver {

    private final ObjectMapper objectMapper;
    private final SchemaRegistry schemaRegistry;

    public SchemaDeriver(ObjectMapper objectMapper, SchemaRegistry schemaRegistry) {
        this.objectMapper = objectMapper;
        this.schemaRegistry = schemaRegistry;
    }

    public List<SchemaDef> deriveAndPublish(ModelDefinition def, int defVersion, String ownerPack) {
        List<SchemaDef> published = new ArrayList<>();
        Map<String, Vocabulary> vocabs = def.vocabularies();

        for (ObjectType ot : def.objectTypes()) {
            ObjectNode schema = objectMapper.createObjectNode();
            schema.put("type", "object");
            schema.put("title", ot.typeKey());
            ObjectNode props = schema.putObject("properties");
            ArrayNode identity = schema.putArray("x-tioka-identity");
            for (String key : ot.identityKeys()) {
                identity.add(key);
                props.set(key, leafSchema(PathValueKind.TEXT, null, vocabs));
            }
            for (AttrGroup group : ot.groups()) {
                String groupName = group.groupKey().toLowerCase(Locale.ROOT);
                ObjectNode groupSchema = objectMapper.createObjectNode();
                groupSchema.put("type", "object");
                ObjectNode groupProps = groupSchema.putObject("properties");
                for (AttrDef attr : group.attrs()) {
                    groupProps.set(attr.name(), attrSchema(attr, vocabs));
                }
                props.set(groupName, groupSchema);
            }
            String schemaKey = def.defKey() + "." + ot.typeKey();
            published.add(schemaRegistry.publish(schemaKey, defVersion, schema, ownerPack));
        }

        for (StatementType st : def.statementTypes()) {
            ObjectNode schema = objectMapper.createObjectNode();
            schema.put("type", "object");
            schema.put("title", st.typeKey());
            ObjectNode props = schema.putObject("properties");
            props.putObject("typeKey").put("type", "string");
            for (StatementParam param : st.params()) {
                if (param.kind() == PathValueKind.OBJECT || param.kind() == PathValueKind.ARRAY) {
                    continue;
                }
                props.set(param.name(), leafSchema(param.kind(), param.enumVocabulary(), vocabs));
            }
            String schemaKey = def.defKey() + ".statement." + st.typeKey();
            published.add(schemaRegistry.publish(schemaKey, defVersion, schema, ownerPack));
        }
        return List.copyOf(published);
    }

    private ObjectNode attrSchema(AttrDef attr, Map<String, Vocabulary> vocabs) {
        if (attr.kind() == PathValueKind.ARRAY) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("type", "array");
            ObjectNode item = objectMapper.createObjectNode();
            item.put("type", "object");
            ObjectNode itemProps = item.putObject("properties");
            for (AttrDef child : attr.children()) {
                itemProps.set(child.name(), attrSchema(child, vocabs));
            }
            node.set("items", item);
            return node;
        }
        if (attr.kind() == PathValueKind.OBJECT) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("type", "object");
            ObjectNode props = node.putObject("properties");
            for (AttrDef child : attr.children()) {
                props.set(child.name(), attrSchema(child, vocabs));
            }
            return node;
        }
        return leafSchema(attr.kind(), attr.enumVocabulary(), vocabs);
    }

    private ObjectNode leafSchema(PathValueKind kind, String enumVocab, Map<String, Vocabulary> vocabs) {
        ObjectNode node = objectMapper.createObjectNode();
        switch (kind) {
            case AMOUNT, MEASURE, RATIO, AREA -> node.put("type", "number");
            case PERIOD -> node.put("type", "object");
            case ENUM -> {
                node.put("type", "string");
                Vocabulary v = enumVocab == null ? null : vocabs.get(enumVocab);
                if (v != null && !v.values().isEmpty()) {
                    ArrayNode en = node.putArray("enum");
                    for (String value : v.values()) {
                        en.add(value);
                    }
                }
            }
            default -> node.put("type", "string");
        }
        return node;
    }
}
