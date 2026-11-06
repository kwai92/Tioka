package com.tioka.server.arch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.framework.security.PublicApi;
import com.tioka.framework.security.RequirePermission;
import com.tioka.model.api.gateway.ModelGateway;
import com.tioka.model.application.OpenAiCompatibleClient;
import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.library.freeze.FreezingArchRule;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.Collection;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

/**
 * ENG-10：全仓架构守护。M0 尚未满足的规则用 FreezingArchRule 冻结现存违例。
 */
@AnalyzeClasses(packages = "com.tioka", importOptions = ImportOption.DoNotIncludeTests.class)
class TiokaArchitectureTest {

    @ArchTest
    static final ArchRule controllersMustDeclarePermission = FreezingArchRule.freeze(
            methods().that().areDeclaredInClassesThat().areAnnotatedWith(RestController.class)
                    .and().arePublic()
                    .should().beAnnotatedWith(RequirePermission.class)
                    .orShould().beAnnotatedWith(PublicApi.class)
                    .orShould().beDeclaredInClassesThat().areAnnotatedWith(RequirePermission.class)
                    .because("Controller 方法必须声明 @RequirePermission 或 @PublicApi（ADR-015）"));

    @ArchTest
    static final ArchRule onlyModelBizTalksToModelHttp = FreezingArchRule.freeze(
            noClasses().that().resideOutsideOfPackage("com.tioka.model..")
                    .should().dependOnClassesThat().areAssignableTo(OpenAiCompatibleClient.class)
                    .because("仅 model-biz 可直接依赖模型 HTTP 客户端（ADR-017）"));

    @ArchTest
    static final ArchRule serverDependsOnlyOnApi = FreezingArchRule.freeze(
            noClasses().that().resideInAPackage("com.tioka.server..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "com.tioka..application..",
                            "com.tioka..infrastructure..",
                            "com.tioka..domain..")
                    .because("tioka-server 只应依赖各域 api 与 framework（ADR-030）"));

    /** 现存 synchronized 由 ORC-01 / RT 新内核清除；此处冻结库存，禁止新增。 */
    @ArchTest
    static final ArchRule noSynchronizedInApplication = FreezingArchRule.freeze(
            noMethods().that().areDeclaredInClassesThat()
                    .resideInAnyPackage("..application..", "..domain..")
                    .should().haveModifier(JavaModifier.SYNCHRONIZED)
                    .because("应用/领域层禁止 synchronized，用 DB/Redis 锁（ADR-016）"));

    /** ENG-05：生产代码不得 new ObjectMapper，统一注入 Spring Bean。 */
    @ArchTest
    static final ArchRule noNewObjectMapper =
            noClasses().should().callConstructor(ObjectMapper.class)
                    .because("统一注入 Spring ObjectMapper（ENG-05）");

    /** ENG-05：业务 ID 禁止截断 UUID，统一 Ids.newId。 */
    @ArchTest
    static final ArchRule noTruncatedUuid = FreezingArchRule.freeze(
            noClasses().should().callMethod(java.util.UUID.class, "randomUUID")
                    .because("业务 ID 使用 Ids.newId；随机口令/令牌等非 ID 场景可留在冻结库存（ENG-05）"));

    @ArchTest
    static final ArchRule onlyRuntimeApiExecutesAgents = FreezingArchRule.freeze(
            noClasses().that().resideInAnyPackage(
                            "com.tioka.chat..",
                            "com.tioka.flow..",
                            "com.tioka.orchestration..",
                            "com.tioka.connector..")
                    .should().dependOnClassesThat().resideInAnyPackage("com.tioka.runtime.application..")
                    .because("一个内核：非 runtime 不得直接依赖 runtime.application（ADR-016）"));

    @ArchTest
    static final ArchRule noModelCallOnRequestThread = FreezingArchRule.freeze(
            noClasses().that().areAnnotatedWith(RestController.class)
                    .should().dependOnClassesThat().areAssignableTo(ModelGateway.class)
                    .because("请求线程不得直接依赖 ModelGateway（ADR-029 第 21 条）"));

    /** ENG-04b：RuntimeTestHooks 仅允许在 gate profile 注册。 */
    @ArchTest
    static final ArchRule testHooksOnlyInGateProfile =
            classes().that().haveSimpleName("RuntimeTestHooks")
                    .should(beAnnotatedWithGateProfile())
                    .because("RuntimeTestHooks 只能在 @Profile(\"gate\") 下注册（ENG-04b）");

    private static ArchCondition<JavaClass> beAnnotatedWithGateProfile() {
        return new ArchCondition<>("be annotated with @Profile(\"gate\")") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                boolean ok = item.getAnnotations().stream().anyMatch(TiokaArchitectureTest::isGateProfile);
                if (!ok) {
                    events.add(SimpleConditionEvent.violated(item,
                            item.getFullName() + " 缺少 @Profile(\"gate\")"));
                } else {
                    events.add(SimpleConditionEvent.satisfied(item,
                            item.getFullName() + " 已标注 gate profile"));
                }
            }
        };
    }

    private static boolean isGateProfile(JavaAnnotation<?> ann) {
        if (!ann.getRawType().isEquivalentTo(Profile.class)) {
            return false;
        }
        Object raw = ann.get("value").orElse(null);
        if (raw instanceof String[] values) {
            return Arrays.asList(values).contains("gate");
        }
        if (raw instanceof Collection<?> values) {
            return values.stream().map(String::valueOf).anyMatch("gate"::equals);
        }
        return "gate".equals(String.valueOf(raw));
    }
}
