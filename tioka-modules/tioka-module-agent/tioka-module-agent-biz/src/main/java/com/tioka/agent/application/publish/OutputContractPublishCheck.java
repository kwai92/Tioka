package com.tioka.agent.application.publish;

import com.tioka.agent.api.publish.PublishCheck;
import com.tioka.agent.api.spec.AgentSpec;
import com.tioka.agent.api.spec.OutputContract;
import com.tioka.ontology.api.schema.SchemaApi;
import com.tioka.ontology.api.schema.SchemaDef;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * SCH-02：{@code output.schemaRef} 必须存在且已 PUBLISHED。
 * SchemaApi 未启用时，若声明了 output 则报 REF_UNAVAILABLE（特性默认关闭，现有 Agent output=null 不受影响）。
 */
@Component
@Order(25)
public class OutputContractPublishCheck implements PublishCheck {

    private final ObjectProvider<SchemaApi> schemaApi;

    public OutputContractPublishCheck(ObjectProvider<SchemaApi> schemaApi) {
        this.schemaApi = schemaApi;
    }

    @Override
    public List<PublishError> check(AgentSpec spec, PublishContext ctx) {
        OutputContract output = spec.output();
        if (output == null) {
            return List.of();
        }
        List<PublishError> errors = new ArrayList<>();
        String ref = output.schemaRef();
        if (ref == null || ref.isBlank()) {
            errors.add(new PublishError("/output/schemaRef", "SCHEMA_INVALID", "output.schemaRef 不能为空"));
            return errors;
        }
        SchemaApi api = schemaApi.getIfAvailable();
        if (api == null) {
            errors.add(new PublishError(
                    "/output/schemaRef",
                    "REF_UNAVAILABLE",
                    "SchemaApi 未启用（tioka.ontology.schema.enabled），无法校验 schemaRef: " + ref));
            return errors;
        }
        Optional<SchemaDef> def = api.get(ref);
        if (def.isEmpty()) {
            errors.add(new PublishError("/output/schemaRef", "REF_NOT_FOUND", "Schema 不存在: " + ref));
            return errors;
        }
        if (!"PUBLISHED".equalsIgnoreCase(def.get().status())) {
            errors.add(new PublishError(
                    "/output/schemaRef",
                    "REF_UNAVAILABLE",
                    "Schema 未发布: " + ref + " status=" + def.get().status()));
        }
        return errors;
    }
}
