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
}
