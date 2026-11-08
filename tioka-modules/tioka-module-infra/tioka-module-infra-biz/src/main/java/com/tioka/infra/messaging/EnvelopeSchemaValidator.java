package com.tioka.infra.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 开发 / 测试环境校验 MQ 信封（生产可通过 {@code tioka.messaging.validate-envelope=false} 关闭）。
 */
@Component
public class EnvelopeSchemaValidator {

    private static final Logger log = LoggerFactory.getLogger(EnvelopeSchemaValidator.class);
    private static final String CLASSPATH = "contracts/events/mq-envelope-v1.json";

    private final MessagingProperties props;
    private final ObjectMapper mapper;
    private final JsonSchema schema;

    public EnvelopeSchemaValidator(MessagingProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        this.schema = loadSchema();
    }

    public void validate(JsonNode envelope) {
        if (!props.isValidateEnvelope() || schema == null) {
            return;
        }
        Set<ValidationMessage> errors = schema.validate(envelope);
        if (!errors.isEmpty()) {
            String detail = errors.stream().map(ValidationMessage::getMessage).collect(Collectors.joining("; "));
            throw new IllegalArgumentException("信封不符合 mq-envelope-v1：" + detail);
        }
    }

    private JsonSchema loadSchema() {
        try {
            ClassPathResource resource = new ClassPathResource(CLASSPATH);
            if (!resource.exists()) {
                log.warn("未找到 {}，跳过信封 schema 校验", CLASSPATH);
                return null;
            }
            try (InputStream in = resource.getInputStream()) {
                JsonNode node = mapper.readTree(in);
                return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(node);
            }
        } catch (Exception e) {
            log.warn("加载信封 schema 失败，跳过校验: {}", e.toString());
            return null;
        }
    }
}
