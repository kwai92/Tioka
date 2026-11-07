package com.tioka.server.arch;

import com.baomidou.mybatisplus.annotation.TableName;
import com.tioka.framework.persistence.AppendOnlyDO;
import com.tioka.framework.persistence.BaseDO;
import com.tioka.framework.persistence.ImmutableSnapshotDO;
import com.tioka.framework.persistence.MutableRecordDO;
import com.tioka.framework.persistence.TechnicalRecordDO;
import com.tioka.framework.persistence.WorkspaceAwareDO;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class PersistenceArchTest {
    static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.tioka");
    }

    @Test
    void noSpringJdbcInProductionCode() {
        noClasses()
                .that().resideInAPackage("com.tioka..")
                .should().dependOnClassesThat().resideInAPackage("org.springframework.jdbc..")
                .because("禁止 JdbcTemplate 等直接 JDBC；使用 MyBatis-Plus")
                .check(classes);
    }

    @Test
    void applicationMustNotDependOnMappers() {
        noClasses()
                .that().resideInAPackage("com.tioka..application..")
                .should().dependOnClassesThat().resideInAPackage("com.tioka..infrastructure.mapper..")
                .because("application 只能依赖 Repository")
                .check(classes);
    }

    @Test
    void tableNameEntitiesMustExtendAgreedBase() {
        classes()
                .that().areAnnotatedWith(TableName.class)
                .should(new ArchCondition<>("extend agreed persistence base") {
                    @Override
                    public void check(JavaClass item, ConditionEvents events) {
                        // RuntimeTurnDO：M0-03 明确无逻辑删、不继承 BaseDO；无 @TableName 时本规则本就不适用
                        boolean ok = item.isAssignableTo(BaseDO.class)
                                || item.isAssignableTo(WorkspaceAwareDO.class)
                                || item.isAssignableTo(ImmutableSnapshotDO.class)
                                || item.isAssignableTo(AppendOnlyDO.class)
                                || item.isAssignableTo(MutableRecordDO.class)
                                || item.isAssignableTo(TechnicalRecordDO.class)
                                || "RuntimeTurnDO".equals(item.getSimpleName())
                                // AST-01：agent_draft 契约仅有 update_at/by + revision，无 create/deleted
                                || "AgentDraftDO".equals(item.getSimpleName())
                                // MH-04：model_default_plan 仅 purpose PK + update_at/by
                                || "ModelDefaultPlanDO".equals(item.getSimpleName())
                                // TIMESTAMPTZ 列用 Instant，不可继承 LocalDateTime 基类（PG JDBC 拒绝 TIMESTAMPTZ→LocalDateTime）
                                || "SysApiKeyDO".equals(item.getSimpleName())
                                || "SysGroupDO".equals(item.getSimpleName())
                                || "SysResourceAclDO".equals(item.getSimpleName());
                        if (!ok) {
                            events.add(SimpleConditionEvent.violated(item,
                                    item.getName() + " 未继承约定 DO 基类"));
                        }
                    }
                })
                .because("所有 @TableName 实体必须继承约定基类")
                .check(classes);
    }
}
