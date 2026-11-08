package com.tioka.connector.sdk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tioka.connector.api.sdk.ConnectorSpec;
import com.tioka.connector.api.sdk.ConnectorTypeHandler;
import org.springframework.stereotype.Component;

/**
 * 桩连接器类型：供 {@code ConnectorSdkContractTest} 跑通规格/凭证/游标/限流/健康。
 */
@Component
public class FakeConnectorTypeHandler implements ConnectorTypeHandler {

    public static final String TYPE = "fake";

    private final ConnectorSpec spec;

    public FakeConnectorTypeHandler(ObjectMapper objectMapper) {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        ObjectNode baseUrl = props.putObject("baseUrl");
        baseUrl.put("type", "string");
        baseUrl.put("description", "上游 HTTP 基址（出网经 EgressGuard）");
        ObjectNode path = props.putObject("path");
        path.put("type", "string");
        schema.putArray("required").add("baseUrl");
        this.spec = new ConnectorSpec(TYPE, schema, true, true, false, false);
    }

    public FakeConnectorTypeHandler() {
        this(new ObjectMapper());
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public ConnectorSpec spec() {
        return spec;
    }
}
