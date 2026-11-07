package com.tioka.framework.messaging;

/**
 * 幂等消费者基类（ORC-02，施工设计 M2-01）。
 *
 * <p>子类注册为 Spring Bean；投递由 infra-biz 适配器完成：先
 * {@code INSERT sys_consumed … ON CONFLICT DO NOTHING}，影响 0 行则跳过，否则在事务内
 * {@link #handle}；异常回滚后由 Broker / in-memory 总线重投。
 *
 * <p>本包只放抽象签名；具体适配与死信写入由 PK-02 实现。
 */
public abstract class IdempotentConsumer<P> {

    /** 唯一消费组名，例 {@code orch-dispatcher}。 */
    protected abstract String consumerName();

    protected abstract Class<P> payloadType();

    /** 业务处理；必须自身可重入；由适配器在事务与 {@code runAs} 上下文中调用。 */
    protected abstract void handle(EventEnvelope envelope, P payload);

    /**
     * 订阅主题（RocketMQ Topic / in-memory 路由）。默认 {@code null} 表示接受任意主题
     *（仅测试桩；生产消费者应覆盖）。
     */
    protected String topic() {
        return null;
    }

    /**
     * Tag 订阅表达式；默认 {@code *}。需要按事件名过滤时覆盖为具体事件名。
     */
    protected String tagExpression() {
        return "*";
    }

    /** 适配器读取消费组名（不改动抽象签名）。 */
    public final String resolvedConsumerName() {
        return consumerName();
    }

    /** 适配器读取订阅主题。 */
    public final String resolvedTopic() {
        return topic();
    }

    /** 适配器读取 Tag 表达式。 */
    public final String resolvedTagExpression() {
        return tagExpression();
    }

    /** 适配器读取 payload 类型。 */
    public final Class<P> resolvedPayloadType() {
        return payloadType();
    }

    /** 适配器在 runAs / 事务上下文中回调业务 {@link #handle}。 */
    @SuppressWarnings("unchecked")
    public final void dispatch(EventEnvelope envelope, Object payload) {
        handle(envelope, (P) payload);
    }
}
