package com.tioka.agent.api.spec;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * AgentSpec 专用序列化：快照拒绝未知字段；校验共用 agent-spec-v1.json。
 */
public final class AgentSpecJson {
    private static final JsonSchema SCHEMA = loadSchema();

    private AgentSpecJson() {}

    /** 基于 Spring ObjectMapper 拷贝，开启 FAIL_ON_UNKNOWN_PROPERTIES。 */
    public static ObjectMapper strictMapper(ObjectMapper springMapper) {
        return springMapper.copy()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    public static AgentSpec parse(ObjectMapper springMapper, String json) {
        try {
            return strictMapper(springMapper).readValue(json, AgentSpec.class);
        } catch (Exception e) {
            throw new BizException(ErrorCode.BAD_REQUEST, "AgentSpec 解析失败: " + e.getMessage());
        }
    }

    public static String write(ObjectMapper springMapper, AgentSpec spec) {
        try {
            return strictMapper(springMapper).writeValueAsString(spec);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static List<String> validateSchema(ObjectMapper springMapper, AgentSpec spec) {
        try {
            JsonNode node = strictMapper(springMapper).valueToTree(spec);
            Set<ValidationMessage> errors = SCHEMA.validate(node);
            List<String> out = new ArrayList<>();
            for (ValidationMessage m : errors) {
                out.add(m.getMessage());
            }
            return out;
        } catch (Exception e) {
            return List.of("schema validate failed: " + e.getMessage());
        }
    }

    public static void requireSchema(ObjectMapper springMapper, AgentSpec spec) {
        List<String> errors = validateSchema(springMapper, spec);
        if (!errors.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "AgentSpec 不符合 schema: " + errors.get(0));
        }
    }

    private static JsonSchema loadSchema() {
        try (InputStream in = AgentSpecJson.class.getResourceAsStream("/schema/agent-spec-v1.json")) {
            if (in == null) {
                throw new IllegalStateException("classpath:/schema/agent-spec-v1.json 缺失");
            }
            return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(in);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}
