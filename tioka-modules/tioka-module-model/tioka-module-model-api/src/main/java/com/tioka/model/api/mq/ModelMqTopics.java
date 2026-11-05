package com.tioka.model.api.mq;

public final class ModelMqTopics {
    public static final String TOPIC = "tioka-model";
    public static final String TAG_PROVIDER_CHANGED = "provider.changed";
    public static final String TAG_ROUTING_PUBLISHED = "routing.published";
    public static final String TAG_USAGE_RECORDED = "usage.recorded";
    public static final String TAG_FAILOVER_SWITCHED = "failover.switched";
    private ModelMqTopics() {}
}
