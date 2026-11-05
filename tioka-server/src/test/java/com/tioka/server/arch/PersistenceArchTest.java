package com.tioka.server.arch;

import com.baomidou.mybatisplus.annotation.TableName;
import com.tioka.framework.persistence.AppendOnlyDO;
import com.tioka.framework.persistence.BaseDO;
import com.tioka.framework.persistence.ImmutableSnapshotDO;
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
                        boolean ok = item.isAssignableTo(BaseDO.class)
                                || item.isAssignableTo(WorkspaceAwareDO.class)
                                || item.isAssignableTo(ImmutableSnapshotDO.class)
                                || item.isAssignableTo(AppendOnlyDO.class);
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
