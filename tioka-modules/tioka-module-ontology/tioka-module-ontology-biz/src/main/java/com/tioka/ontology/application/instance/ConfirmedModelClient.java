package com.tioka.ontology.application.instance;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 确认模型拉取端口（M3-03 §7 步骤 2：调业务连接器 {@code getConfirmedModel(instanceKey, version)}）。
 * 出站能力归 CON-07a 业务连接器；本批定义端口，生产适配器随 CON-07a 接线（批次文档登记）。
 */
public interface ConfirmedModelClient {

    /** 拉取业务系统确认的完整模型；不可得时抛异常（消费重试 → 死信）。 */
    JsonNode fetch(String instanceKey, int version);
}
