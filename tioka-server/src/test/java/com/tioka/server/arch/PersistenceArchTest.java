package com.tioka.server.arch;

import com.baomidou.mybatisplus.annotation.TableName;
import com.tioka.framework.persistence.AppendOnlyDO;
import com.tioka.framework.persistence.BaseDO;
import com.tioka.framework.persistence.ImmutableSnapshotDO;
import com.tioka.framework.persistence.MutableRecordDO;
import com.tioka.framework.persistence.PersistenceProperties;
import com.tioka.framework.persistence.TechnicalRecordDO;
import com.tioka.framework.persistence.WorkspaceAwareDO;

import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
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
    void mapperInterfacesMustNotUseAnnotationSql() {
        noClasses()
                .that().resideInAPackage("com.tioka..infrastructure.mapper..")
                .should().dependOnClassesThat().haveFullyQualifiedName("org.apache.ibatis.annotations.Select")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("org.apache.ibatis.annotations.Insert")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("org.apache.ibatis.annotations.Update")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("org.apache.ibatis.annotations.Delete")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("org.apache.ibatis.annotations.SelectProvider")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("org.apache.ibatis.annotations.InsertProvider")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("org.apache.ibatis.annotations.UpdateProvider")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("org.apache.ibatis.annotations.DeleteProvider")
                .because("自定义 SQL 须放 mapper/*.xml（48-CS §4）；含 *Provider")
                .check(classes);
    }

    @Test
    void applicationAndKernelMustNotDependOnMappers() {
        noClasses()
                .that().resideInAnyPackage("com.tioka..application..", "com.tioka..kernel..")
                .and().resideOutsideOfPackage("com.tioka..kernel.infrastructure..")
                .should().dependOnClassesThat().resideInAPackage("com.tioka..infrastructure.mapper..")
                .because("application/kernel 只能依赖 Repository，禁止直持 Mapper")
                .check(classes);
    }

    /** 鉴权路径例外：有 workspace_id 列但必须 exempt。 */
    private static final Set<String> AUTH_EXEMPT_WITH_WORKSPACE = Set.of("sys_member", "sys_api_key");

    @Test
    void tableWithoutWorkspaceIdMustBeScopeExempt() {
        Set<String> exempt = new PersistenceProperties().getWorkspaceScopeExemptTables().stream()
                .map(t -> t.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        classes()
                .that().areAnnotatedWith(TableName.class)
                .and().areNotAssignableTo(WorkspaceAwareDO.class)
                .should(new ArchCondition<>("be in ScopeLine exempt when no workspaceId field") {
                    @Override
                    public void check(JavaClass item, ConditionEvents events) {
                        if (hasWorkspaceIdField(item)) {
                            return;
                        }
                        TableName tn = item.getAnnotationOfType(TableName.class);
                        String table = tn.value() == null ? "" : tn.value().toLowerCase(Locale.ROOT);
                        if (!exempt.contains(table)) {
                            events.add(SimpleConditionEvent.violated(item,
                                    item.getName() + " @TableName(" + table
                                            + ") 无 workspaceId，须登记 tioka.persistence.workspace-scope-exempt-tables"));
                        }
                    }
                })
                .because("无 workspace 列的表必须 exempt，否则 deny-by-default 会注入非法 SQL")
                .check(classes);
    }

    @Test
    void tableWithWorkspaceIdMustNotBeScopeExempt() {
        Set<String> exempt = new PersistenceProperties().getWorkspaceScopeExemptTables().stream()
                .map(t -> t.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        classes()
                .that().areAnnotatedWith(TableName.class)
                .should(new ArchCondition<>("not be ScopeLine exempt when workspace-scoped") {
                    @Override
                    public void check(JavaClass item, ConditionEvents events) {
                        boolean scoped = item.isAssignableTo(WorkspaceAwareDO.class) || hasWorkspaceIdField(item);
                        if (!scoped) {
                            return;
                        }
                        TableName tn = item.getAnnotationOfType(TableName.class);
                        String table = tn.value() == null ? "" : tn.value().toLowerCase(Locale.ROOT);
                        if (AUTH_EXEMPT_WITH_WORKSPACE.contains(table)) {
                            return;
                        }
                        if (exempt.contains(table)) {
                            events.add(SimpleConditionEvent.violated(item,
                                    item.getName() + " @TableName(" + table
                                            + ") 有 workspaceId，禁止进入 workspace-scope-exempt-tables"));
                        }
                    }
                })
                .because("空间表默认过滤；仅 sys_member/sys_api_key 可有列且 exempt")
                .check(classes);
    }

    private static boolean hasWorkspaceIdField(JavaClass item) {
        return item.getAllFields().stream().anyMatch(f -> "workspaceId".equals(f.getName()));
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
                                || item.isAssignableTo(AppendOnlyDO.class)
                                || item.isAssignableTo(MutableRecordDO.class)
                                || item.isAssignableTo(TechnicalRecordDO.class)
                                // AST-01：agent_draft 契约仅有 update_at/by + revision，无 create/deleted
                                || "AgentDraftDO".equals(item.getSimpleName())
                                // MH-04：model_default_plan 仅 purpose PK + update_at/by
                                || "ModelDefaultPlanDO".equals(item.getSimpleName())
                                // 历史 Instant 映射；新表优先 MutableRecordDO + UtcLocalDateTimeTypeHandler
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
