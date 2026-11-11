package tioka.pack.lease;

import com.tioka.ontology.api.def.ModelDefinition;
import com.tioka.ontology.application.pack.CelFunction;
import com.tioka.ontology.application.pack.ModelValidator;
import com.tioka.ontology.application.pack.PromptBundle;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** LSE-01/02/04 领域包入口：元数据、定义、Prompt、校验器、CEL 函数清单。 */
class LeaseDomainPackTest {

    private final LeaseDomainPack pack = new LeaseDomainPack();

    @Test
    void 元数据与权限口径() {
        assertEquals("lease", pack.packKey());
        assertEquals("1.0.0", pack.version());
        assertNotNull(pack.definition());
        assertTrue(pack.folding().isPresent(), "LSE-03 折叠引擎必须装配");
        assertTrue(pack.projections().isEmpty(), "projections 暂空（无 CON-07a，偏差登记）");
    }

    @Test
    void 模型定义五支齐全() {
        ModelDefinition def = pack.definition();
        assertEquals("lease.contract", def.defKey());
        assertEquals("@contract.model", def.entityType());
        // 骨架（party/relation）+ 标的（subject）+ 商务（feeItem/deposit/crossRule）
        assertEquals(6, def.objectTypes().size());
        // 权利义务五类
        assertEquals(5, def.statementTypes().size());
        // 19 个主题
        assertEquals(19, def.topics().size());
        // 修改类型 → 变更操作词表
        assertTrue(def.modifications().typeToOps().size() >= 10);
        assertTrue(def.modifications().typeToOps().get("迁址").contains("@ARCHIVE_REBUILD"));
        // V01〜V16 全部登记
        assertEquals(16, def.validatorIds().size());
        assertEquals("lease.V01.zero-not-free", def.validatorIds().get(0));
        assertEquals("lease.V16.coverage", def.validatorIds().get(15));
    }

    @Test
    void 校验器清单与ID对齐() {
        List<ModelValidator> validators = pack.validators();
        assertEquals(16, validators.size());
        for (int i = 0; i < validators.size(); i++) {
            assertEquals(pack.definition().validatorIds().get(i), validators.get(i).validatorId(),
                    "第 " + i + " 位校验器 ID 与定义登记不一致");
        }
    }

    @Test
    void prompt按步骤与对象类型索引_静态资产落jar() {
        PromptBundle prompts = pack.prompts();
        // 步骤 × 对象类型：step1/2 + step3×5 + step4×5 + step5/6
        assertEquals(14, prompts.byStepAndType().size());
        String feeItemPrompt = prompts.byStepAndType().get("step3:feeItem");
        assertNotNull(feeItemPrompt);
        // 易错点直接写进 Prompt 的「注意」段（M3-04 L71）
        assertTrue(feeItemPrompt.contains("0 元不是免租"));
        assertTrue(feeItemPrompt.contains("一次性金额不是月额"));
        assertTrue(feeItemPrompt.contains("空白不是 0"));
        assertNotNull(prompts.byStepAndType().get("step3:deposit"));
        assertTrue(prompts.byStepAndType().get("step3:deposit").contains("违约金和滞纳金不是押金"));
        assertNotNull(prompts.byStepAndType().get("step5:modifications"));
    }

    @Test
    void celFunctions八个全登记() {
        List<String> names = pack.celFunctions().stream().map(CelFunction::name).toList();
        assertEquals(List.of("freeMonths", "escalationRate", "depositMonths", "cureDays",
                "penaltyCap", "overdueDaysToTerminate", "overdueTimesToTerminate", "hasStatement"), names);
        for (CelFunction f : pack.celFunctions()) {
            assertNotNull(f.implementation(), f.name() + " 必须带实现（登记≠占位）");
        }
    }

    @Test
    void 配置_问题缺省进复核_可覆盖() {
        LeasePackConfig config = new LeasePackConfig();
        assertEquals("REVIEW", config.question("N1"));
        assertEquals("REVIEW", config.question("N2"));
        assertTrue(config.isReview("N5"));
        // 业务确认后改配置不改代码：换配置资源验证（test resources config-override.properties）
        LeasePackConfig custom = new LeasePackConfig("/config-override.properties");
        assertEquals("CONTINUE", custom.question("N1"));
    }
}
