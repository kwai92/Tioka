package com.tioka.server.arch;

import com.tioka.framework.messaging.IdempotentConsumer;
import com.tioka.framework.security.CredentialCipher;
import com.tioka.orchestration.api.dispatch.OrchActionType;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.freeze.FreezingArchRule;

import javax.crypto.Cipher;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * M2 静态闸门（M2-00 第 7.2 节）。现存违例进冻结库；各包只能从冻结库删除自己的条目。
 */
@AnalyzeClasses(packages = "com.tioka", importOptions = ImportOption.DoNotIncludeTests.class)
class M2ArchitectureTest {

    /**
     * 业务模块不直接使用 RocketMQ 客户端发消息，跨域事件一律经 outbox。
     * 允许：infra（中继/适配器）、framework.messaging（信封类型）。
     */
    @ArchTest
    static final ArchRule crossDomainEventsViaOutbox = FreezingArchRule.freeze(
            noClasses().that().resideOutsideOfPackages(
                            "com.tioka.infra..",
                            "com.tioka.framework.messaging..",
                            "com.tioka.server..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "org.apache.rocketmq..",
                            "org.apache.rocketmq.spring..")
                    .because("跨域事件一律经 outbox，禁止业务模块直连 RocketMQ（M2-00 7.2）"));

    /**
     * 所有 MQ 监听器须继承 {@link IdempotentConsumer}（经统一幂等适配器注册）。
     * 现存非继承监听器进冻结库；M2 新建消费者不得再进冻结库。
     */
    @ArchTest
    static final ArchRule consumersAreIdempotent = FreezingArchRule.freeze(
            classes().that().areAnnotatedWith("org.apache.rocketmq.spring.annotation.RocketMQMessageListener")
                    .should().beAssignableTo(IdempotentConsumer.class)
                    .allowEmptyShould(true)
                    .because("消费者须经 IdempotentConsumer 注册（M2-00 7.2 / M2-01）"));

    /** 只有 Connector 模块可以依赖飞书开放平台 SDK。 */
    @ArchTest
    static final ArchRule onlyConnectorTalksToFeishu = FreezingArchRule.freeze(
            noClasses().that().resideOutsideOfPackage("com.tioka.connector..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "com.lark..",
                            "com.larksuite..",
                            "com.lark.oapi..")
                    .because("只有 Connector 可访问飞书开放平台（M2-00 7.2）"));

    /**
     * 凭证类加解密只经 {@link CredentialCipher}；其它 javax.crypto.Cipher 调用进冻结库
     *（如 LocalSecretStore 的遗留 ECB，由 FND-08 清除）。
     */
    @ArchTest
    static final ArchRule credentialsOnlyViaCipher = FreezingArchRule.freeze(
            noClasses().that().resideOutsideOfPackage("com.tioka.framework.security..")
                    .should().callMethod(Cipher.class, "getInstance", String.class)
                    .because("凭证只经 CredentialCipher 读写（M2-00 7.2 / FND-08）；遗留 ECB 进冻结库"));

    /**
     * M2 规则动作不得引用 FLOW 派工（发布校验拒绝 flow；M3 FLW-06 接通时删除本规则）。
     */
    @ArchTest
    static final ArchRule noFlowActionInM2 = FreezingArchRule.freeze(
            noClasses().that().resideInAnyPackage(
                            "com.tioka.orchestration.application..",
                            "com.tioka.orchestration.infrastructure..")
                    .should().accessField(OrchActionType.class, "FLOW")
                    .because("M2 规则发布拒绝 flow 动作（约束 F23 / M2-00 7.2）"));
}
