package com.tioka.ontology.api.mq;

/**
 * 本体 / Schema / 文档解析跨域事件名（P3-00a；禁止在 biz 内硬编码）。
 * <p>权威 payload：{@code docs/contracts/events/tioka.*.v1.json}
 */
public final class OntologyMqTopics {

    public static final String TOPIC = "tioka-ontology";

    public static final String DOCUMENT_READY = "tioka.ontology.document_ready.v1";
    public static final String INSTANCE_CONFIRMED = "tioka.ontology.instance_confirmed.v1";
    public static final String STATE_FOLDED = "tioka.ontology.state_folded.v1";
    public static final String SCHEMA_PUBLISHED = "tioka.schema.published.v1";
    public static final String DOCUMENT_PARSED = "tioka.docparse.document_parsed.v1";

    /**
     * ONT-02 入站：业务系统确认事件（M3-03 §7 / contract-model-confirmed-v1.json）。
     * 业务系统经 RocketMQ 直投 {@code tioka-biz-{system}} 主题（M3-03 §16）；
     * 消费侧 {@code ConfirmIngestor} 按 tag 过滤。
     */
    public static final String BIZ_LEASE_TOPIC = "tioka-biz-lease";
    public static final String BIZ_EVENT_MODEL_CONFIRMED = "contract.model.confirmed";

    private OntologyMqTopics() {
    }
}
