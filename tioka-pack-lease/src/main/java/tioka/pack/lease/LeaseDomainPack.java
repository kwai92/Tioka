package tioka.pack.lease;

import com.tioka.ontology.api.def.ModelDefinition;
import com.tioka.ontology.api.def.PathValueKind;
import com.tioka.ontology.api.doc.DocumentView;
import com.tioka.ontology.application.pack.CelFunction;
import com.tioka.ontology.application.pack.DomainPack;
import com.tioka.ontology.application.pack.FoldingEngine;
import com.tioka.ontology.application.pack.ModelValidator;
import com.tioka.ontology.application.pack.PromptBundle;
import com.tioka.ontology.application.pack.ProjectionHook;
import tioka.pack.lease.cel.LeaseCelFunctions;
import tioka.pack.lease.fold.LeaseFoldEngine;
import tioka.pack.lease.validators.V01ZeroNotFreeValidator;
import tioka.pack.lease.validators.V02OneoffNotMonthlyValidator;
import tioka.pack.lease.validators.V03CommissionNotTaxValidator;
import tioka.pack.lease.validators.V04AreaNotDerivedValidator;
import tioka.pack.lease.validators.V05AmountTraceableValidator;
import tioka.pack.lease.validators.V06BlankNotZeroValidator;
import tioka.pack.lease.validators.V07OneFactOneItemValidator;
import tioka.pack.lease.validators.V08FeeIdentityValidator;
import tioka.pack.lease.validators.V09SubjectScopeValidator;
import tioka.pack.lease.validators.V10PeriodCompleteValidator;
import tioka.pack.lease.validators.V11AddVsAdjustValidator;
import tioka.pack.lease.validators.V12RenewalAmountCompleteValidator;
import tioka.pack.lease.validators.V13DepositNotCopiedValidator;
import tioka.pack.lease.validators.V14PartyChangeLegalValidator;
import tioka.pack.lease.validators.V15EffectiveDateGroundedValidator;
import tioka.pack.lease.validators.V16CoverageValidator;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * LSE-01〜04 租赁领域包入口（M3-04 §1；ONT-05 DomainPack SPI；权限仅 cpu，纯函数）。
 *
 * <ul>
 *   <li>LSE-01：模型定义（五支）+ Prompt（步骤 × 对象类型，jar 内 prompts/ 静态资产）；</li>
 *   <li>LSE-02：V01〜V16 十六条校验器；</li>
 *   <li>LSE-03：折叠引擎（五阶段 + R-1〜R-16；projections() 暂空——无 CON-07a，
 *       written_back=true 语义登记为「平台侧折叠完成、无外部写回」，批次偏差登记）；</li>
 *   <li>LSE-04：八个 CEL 函数。</li>
 * </ul>
 */
public class LeaseDomainPack implements DomainPack {

    public static final String PACK_KEY = "lease";
    public static final String VERSION = "1.0.0";
    public static final String DEF_KEY = "lease.contract";

    private final LeasePackConfig config;

    public LeaseDomainPack() {
        this(new LeasePackConfig());
    }

    /** 测试 / 定制入口。 */
    public LeaseDomainPack(LeasePackConfig config) {
        this.config = config;
    }

    @Override
    public String packKey() {
        return PACK_KEY;
    }

    @Override
    public String version() {
        return VERSION;
    }

    /** LSE-01：模型定义（与 definitions/lease.contract.yaml 同口径；五支）。 */
    @Override
    public ModelDefinition definition() {
        // 骨架：当事方
        ModelDefinition.ObjectType party = new ModelDefinition.ObjectType(
                "party", "parties", List.of("name", "role"), List.of(), List.of(), List.of());
        // 骨架：合同关系
        ModelDefinition.ObjectType relation = new ModelDefinition.ObjectType(
                "relation", "relations", List.of("target"), List.of(), List.of(), List.of());
        // 标的与期限
        ModelDefinition.ObjectType subject = new ModelDefinition.ObjectType(
                "subject", "subjects", List.of("code"), List.of(), List.of(), List.of());
        // 商务对象：费用项（CALC/SCHED/PAY/TAX/ABATE）、押金、跨项规则
        ModelDefinition.AttrDef baseAmount = ModelDefinition.AttrDef.leaf("baseAmount", PathValueKind.AMOUNT, "CNY", null, List.of("rent.base"));
        ModelDefinition.AttrDef segments = new ModelDefinition.AttrDef(
                "segments", PathValueKind.ARRAY, null, null, List.of("rent.escalation"), List.of());
        ModelDefinition.AttrDef freeMonths = ModelDefinition.AttrDef.leaf("freeMonths", PathValueKind.AMOUNT, "month", null, List.of("rent.abatement"));
        ModelDefinition.AttrDef condition = ModelDefinition.AttrDef.leaf("condition", PathValueKind.TEXT, null, null, List.of("rent.abatement"));
        ModelDefinition.AttrGroup calc = new ModelDefinition.AttrGroup("CALC", List.of(baseAmount));
        ModelDefinition.AttrGroup sched = new ModelDefinition.AttrGroup("SCHED", List.of(segments));
        ModelDefinition.AttrGroup pay = new ModelDefinition.AttrGroup("PAY", List.of(
                ModelDefinition.AttrDef.leaf("payDay", PathValueKind.TEXT, null, null, List.of("rent.payment")),
                ModelDefinition.AttrDef.leaf("payCycle", PathValueKind.ENUM, null, "payCycles", List.of("rent.payment")),
                ModelDefinition.AttrDef.leaf("payAmount", PathValueKind.AMOUNT, "CNY", null, List.of("rent.payment"))));
        ModelDefinition.AttrGroup tax = new ModelDefinition.AttrGroup("TAX", List.of(
                ModelDefinition.AttrDef.leaf("rate", PathValueKind.AMOUNT, "ratio", null, List.of("fee.tax"))));
        ModelDefinition.AttrGroup abate = new ModelDefinition.AttrGroup("ABATE", List.of(freeMonths, condition));
        ModelDefinition.ObjectType feeItem = new ModelDefinition.ObjectType(
                "feeItem", "feeItems", List.of("feeCode", "subject"),
                List.of(calc, sched, pay, tax, abate), List.of(), List.of());
        ModelDefinition.ObjectType deposit = new ModelDefinition.ObjectType(
                "deposit", "deposits", List.of("subject", "depositType", "originalName"), List.of(),
                List.of(), List.of());
        ModelDefinition.ObjectType crossRule = new ModelDefinition.ObjectType(
                "crossRule", "crossRules", List.of("ruleType", "targets"), List.of(), List.of(), List.of());
        // 权利义务（第一期：必备要点涉及主题）
        ModelDefinition.StatementType obligation = new ModelDefinition.StatementType(
                "OBLIGATION", List.of(new ModelDefinition.StatementParam("action", PathValueKind.ENUM, "actions", List.of())));
        ModelDefinition.StatementType right = new ModelDefinition.StatementType(
                "RIGHT", List.of(new ModelDefinition.StatementParam("rightType", PathValueKind.ENUM, "rightTypes", List.of())));
        ModelDefinition.StatementType consequence = new ModelDefinition.StatementType(
                "CONSEQUENCE", List.of(
                new ModelDefinition.StatementParam("consequenceType", PathValueKind.ENUM, "consequenceTypes", List.of()),
                new ModelDefinition.StatementParam("liabilityReason", PathValueKind.ENUM, "liabilityReasons", List.of())));
        ModelDefinition.StatementType cond = new ModelDefinition.StatementType("CONDITION", List.of());
        ModelDefinition.StatementType procedure = new ModelDefinition.StatementType("PROCEDURE", List.of());

        List<ModelDefinition.Topic> topics = List.of(
                new ModelDefinition.Topic("rent.base", List.of("feeItem"), List.of("OBLIGATION")),
                new ModelDefinition.Topic("rent.abatement", List.of("feeItem"), List.of("CONDITION")),
                new ModelDefinition.Topic("rent.escalation", List.of("feeItem"), List.of()),
                new ModelDefinition.Topic("rent.payment", List.of("feeItem"), List.of("OBLIGATION", "CONSEQUENCE")),
                new ModelDefinition.Topic("rent.freeMonths", List.of("feeItem"), List.of()),
                new ModelDefinition.Topic("deposit.security", List.of("deposit"), List.of("OBLIGATION", "CONSEQUENCE")),
                new ModelDefinition.Topic("fee.management", List.of("feeItem"), List.of("OBLIGATION")),
                new ModelDefinition.Topic("fee.utility", List.of("feeItem"), List.of("OBLIGATION")),
                new ModelDefinition.Topic("fee.promotion", List.of("feeItem"), List.of("OBLIGATION")),
                new ModelDefinition.Topic("fee.tax", List.of("feeItem"), List.of("OBLIGATION")),
                new ModelDefinition.Topic("crossRule.offset", List.of("crossRule"), List.of()),
                new ModelDefinition.Topic("term.delivery", List.of("subject"), List.of("OBLIGATION")),
                new ModelDefinition.Topic("term.fitout", List.of("subject"), List.of("RIGHT", "CONDITION")),
                new ModelDefinition.Topic("term.opening", List.of("subject"), List.of("OBLIGATION")),
                new ModelDefinition.Topic("termination.tenant_default", List.of(), List.of("RIGHT", "CONSEQUENCE")),
                new ModelDefinition.Topic("termination.landlord", List.of(), List.of("RIGHT", "CONSEQUENCE")),
                new ModelDefinition.Topic("termination.early", List.of(), List.of("RIGHT", "CONSEQUENCE")),
                new ModelDefinition.Topic("renewal.renew", List.of(), List.of("RIGHT", "PROCEDURE")),
                new ModelDefinition.Topic("transfer.assign", List.of("party"), List.of("OBLIGATION", "PROCEDURE")));

        Map<String, ModelDefinition.Vocabulary> vocabularies = new LinkedHashMap<>();
        vocabularies.put("contractKinds", new ModelDefinition.Vocabulary("contractKinds",
                List.of("主合同", "补充", "续约", "变更", "终止", "转让", "回购", "甲方变更")));
        vocabularies.put("partyRoles", new ModelDefinition.Vocabulary("partyRoles", List.of("甲方", "乙方")));
        vocabularies.put("relationTypes", new ModelDefinition.Vocabulary("relationTypes",
                List.of("主合同", "补充", "续约", "变更", "终止", "转让")));
        vocabularies.put("areaBases", new ModelDefinition.Vocabulary("areaBases",
                List.of("建筑面积", "使用面积", "计租口径")));
        vocabularies.put("payCycles", new ModelDefinition.Vocabulary("payCycles",
                List.of("月付", "季付", "半年付", "年付")));
        vocabularies.put("crossRuleTypes", new ModelDefinition.Vocabulary("crossRuleTypes",
                List.of("孰高", "孰低", "抵扣")));
        vocabularies.put("actions", new ModelDefinition.Vocabulary("actions",
                List.of("支付", "交付", "装修", "开业", "维修", "保险", "通知", "配合", "恢复原状", "接受检查")));
        vocabularies.put("rightTypes", new ModelDefinition.Vocabulary("rightTypes",
                List.of("解除权", "续租权", "优先承租权", "转租权", "免租权", "减免权", "补救权", "抗辩权")));
        vocabularies.put("consequenceTypes", new ModelDefinition.Vocabulary("consequenceTypes",
                List.of("违约金", "滞纳金", "没收押金", "解除合同", "赔偿损失", "停业整顿")));
        vocabularies.put("liabilityReasons", new ModelDefinition.Vocabulary("liabilityReasons",
                List.of("逾期付款", "逾期交付", "擅自转租", "擅自改动", "违规经营", "安全事故", "提前退租")));
        vocabularies.put("depositTypes", new ModelDefinition.Vocabulary("depositTypes",
                List.of("履约保证金", "租金押金", "装修押金", "水电押金", "物业押金", "其他押金")));

        // 修改类型 → 变更操作（第五支；与 definitions/lease.contract.yaml modifications 同口径）
        Map<String, List<String>> typeToOps = new LinkedHashMap<>();
        typeToOps.put("续约", List.of("@TERM_RENEW"));
        typeToOps.put("调价", List.of("@ITEM_ADJUST"));
        typeToOps.put("免租调整", List.of("@ITEM_ADJUST"));
        typeToOps.put("新增费用", List.of("@ITEM_ADD"));
        typeToOps.put("终止费用", List.of("@ITEM_REMOVE"));
        typeToOps.put("主体变更", List.of("@PARTY_CHANGE"));
        typeToOps.put("交付日期调整", List.of("@DATE_ADJUST"));
        typeToOps.put("计租起始调整", List.of("@DATE_ADJUST"));
        typeToOps.put("提前终止", List.of("@TERM_TERMINATE"));
        typeToOps.put("转让", List.of("@PARTY_TRANSFER"));
        typeToOps.put("回购", List.of("@PARTY_TRANSFER"));
        typeToOps.put("迁址", List.of("@SUBJECT_ADD", "@ARCHIVE_REBUILD"));

        List<String> validatorIds = List.of(
                V01ZeroNotFreeValidator.ID, V02OneoffNotMonthlyValidator.ID,
                V03CommissionNotTaxValidator.ID, V04AreaNotDerivedValidator.ID,
                V05AmountTraceableValidator.ID, V06BlankNotZeroValidator.ID,
                V07OneFactOneItemValidator.ID, V08FeeIdentityValidator.ID,
                V09SubjectScopeValidator.ID, V10PeriodCompleteValidator.ID,
                V11AddVsAdjustValidator.ID, V12RenewalAmountCompleteValidator.ID,
                V13DepositNotCopiedValidator.ID, V14PartyChangeLegalValidator.ID,
                V15EffectiveDateGroundedValidator.ID, V16CoverageValidator.ID);

        return new ModelDefinition(
                1,
                DEF_KEY,
                "@contract.model",
                new ModelDefinition.BusinessKeyRule(List.of("storeCode", "contractNo"), "fileHash"),
                List.of(party, relation, subject, feeItem, deposit, crossRule),
                List.of(obligation, right, consequence, cond, procedure),
                topics,
                vocabularies,
                new ModelDefinition.ModificationVocabulary(typeToOps),
                validatorIds,
                List.of(),
                List.of("feeItems.RENT.abate.freeMonths", "feeItems.RENT.sched.segments",
                        "feeItems.RENT.calc.baseAmount", "deposits.amount",
                        "subjects.S1.area", "subjects.S1.termStart", "subjects.S1.termEnd"));
    }

    /** LSE-01：Prompt（按步骤 × 对象类型索引；jar 内 prompts/ 静态资产）。 */
    @Override
    public PromptBundle prompts() {
        Map<String, String> byStepAndType = new LinkedHashMap<>();
        for (String name : List.of(
                "step1-topics", "step2-inventory",
                "step3-feeItem", "step3-deposit", "step3-subject", "step3-party", "step3-crossRule",
                "step4-OBLIGATION", "step4-RIGHT", "step4-CONSEQUENCE", "step4-CONDITION", "step4-PROCEDURE",
                "step5-modifications", "step6-validate")) {
            String content = resourceText("/prompts/" + name + ".md");
            if (content != null) {
                int dash = name.indexOf('-', "stepN".length());
                String step = name.substring(0, dash > 0 ? dash : name.length());
                String type = dash > 0 ? name.substring(dash + 1) : "";
                byStepAndType.put(step + ":" + type, content);
            }
        }
        return new PromptBundle(byStepAndType);
    }

    /** LSE-02：V01〜V16。 */
    @Override
    public List<ModelValidator> validators() {
        return List.of(
                new V01ZeroNotFreeValidator(),
                new V02OneoffNotMonthlyValidator(),
                new V03CommissionNotTaxValidator(),
                new V04AreaNotDerivedValidator(config),
                new V05AmountTraceableValidator(config),
                new V06BlankNotZeroValidator(),
                new V07OneFactOneItemValidator(),
                new V08FeeIdentityValidator(),
                new V09SubjectScopeValidator(),
                new V10PeriodCompleteValidator(),
                new V11AddVsAdjustValidator(),
                new V12RenewalAmountCompleteValidator(),
                new V13DepositNotCopiedValidator(),
                new V14PartyChangeLegalValidator(),
                new V15EffectiveDateGroundedValidator(),
                new V16CoverageValidator(config));
    }

    /** LSE-03：折叠引擎。 */
    @Override
    public Optional<FoldingEngine> folding() {
        return Optional.of(new LeaseFoldEngine(config));
    }

    /** LSE-03：投影钩子暂空（无 CON-07a；偏差登记，见类注释）。 */
    @Override
    public List<ProjectionHook> projections() {
        return List.of();
    }

    /** LSE-04：八个 CEL 函数。 */
    @Override
    public List<CelFunction> celFunctions() {
        return List.of(
                new CelFunction("freeMonths", "费用项免租月数", LeaseCelFunctions::freeMonths),
                new CelFunction("escalationRate", "费用项年化递增率", LeaseCelFunctions::escalationRate),
                new CelFunction("depositMonths", "押金折合月数", LeaseCelFunctions::depositMonths),
                new CelFunction("cureDays", "补救期天数（工作日按 7/5 换算自然日，待复核口径）", LeaseCelFunctions::cureDays),
                new CelFunction("penaltyCap", "违约金上限", LeaseCelFunctions::penaltyCap),
                new CelFunction("overdueDaysToTerminate", "逾期解约天数门槛", LeaseCelFunctions::overdueDaysToTerminate),
                new CelFunction("overdueTimesToTerminate", "逾期解约次数门槛", LeaseCelFunctions::overdueTimesToTerminate),
                new CelFunction("hasStatement", "过滤后的陈述集合非空", LeaseCelFunctions::hasStatement));
    }

    private static String resourceText(String path) {
        try (InputStream in = LeaseDomainPack.class.getResourceAsStream(path)) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }
}
