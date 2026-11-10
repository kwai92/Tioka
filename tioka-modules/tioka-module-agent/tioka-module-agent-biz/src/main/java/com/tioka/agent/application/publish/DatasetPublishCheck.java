package com.tioka.agent.application.publish;

import com.tioka.agent.api.publish.PublishCheck;
import com.tioka.agent.api.spec.AgentSpec;
import com.tioka.agent.api.spec.Charter;
import com.tioka.connector.api.dataset.DatasetPolicyQueryApi;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * DAT-01（M3-02）：SERVICE 模式（charter.actingAs = SERVICE）绑定的数据集必须有 ENFORCE 策略
 * ——SERVICE 模式下数据集策略是唯一的行级防线（ADR-040「执行」节）。
 * 所有模式校验数据集引用存在。回滚也执行（引用面）。
 */
@Component
@Order(21)
public class DatasetPublishCheck implements PublishCheck {

    private final ObjectProvider<DatasetPolicyQueryApi> datasets;

    public DatasetPublishCheck(ObjectProvider<DatasetPolicyQueryApi> datasets) {
        this.datasets = datasets;
    }

    @Override
    public List<PublishError> check(AgentSpec spec, PublishContext ctx) {
        List<String> refs = spec.datasets() == null ? List.of() : spec.datasets();
        if (refs.isEmpty()) {
            return List.of();
        }
        DatasetPolicyQueryApi api = datasets.getIfAvailable();
        boolean serviceMode = isServiceMode(spec);
        List<PublishError> errors = new ArrayList<>();
        for (int i = 0; i < refs.size(); i++) {
            String name = refs.get(i);
            String path = "/datasets/" + i;
            if (api == null) {
                errors.add(new PublishError(path, "REF_UNAVAILABLE", "数据集校验服务不可用"));
                continue;
            }
            if (!api.exists(name)) {
                errors.add(new PublishError(path, "REF_NOT_FOUND", "数据集不存在: " + name));
                continue;
            }
            if (serviceMode && !api.hasEnforcePolicy(name)) {
                errors.add(new PublishError(path, "SERVICE_MODE_NEEDS_POLICY",
                        "SERVICE 模式绑定的数据集必须有 ENFORCE 策略: " + name));
            }
        }
        return errors;
    }

    private static boolean isServiceMode(AgentSpec spec) {
        try {
            Charter charter = Charter.parse(spec.charter());
            return charter != null && "SERVICE".equalsIgnoreCase(charter.actingAs());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
