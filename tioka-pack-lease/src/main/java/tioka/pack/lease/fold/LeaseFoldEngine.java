package tioka.pack.lease.fold;

import com.tioka.ontology.application.pack.FoldingEngine;
import tioka.pack.lease.LeasePackConfig;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * LSE-03 租赁折叠引擎（M3-04 §1 LSE-03，严格按规格「折叠与租约状态」五阶段）：
 *
 * <pre>
 * 阶段 1  修改声明 → 变更操作（@TERM_RENEW / @ITEM_ADJUST / @ITEM_ADD / @ITEM_REMOVE / @PARTY_TRANSFER / @ARCHIVE_REBUILD…）
 * 阶段 2  结构（合同、关系、租期、关键日期、主体变更，按生效日）
 * 阶段 3  建对象（只建空壳）→ 取值（作用范围优先级 → 生效日 → 签署日 → 文档内顺序），逐分段生成
 * 阶段 4  续约补齐（只填续约期内没被覆盖的部分）
 * 阶段 5  边界（终止、缩短、结束类最后统一应用）
 * </pre>
 *
 * 折叠规则：R-1〜R-5（规格明确）+ R-06〜R-16（其余 11 条；规格原文不在 M3-04 落地文档内，
 * 按可检测模式实现并登记偏差）。[?] 业务问题（N1/N2 等）经 {@link LeasePackConfig} 缺省「进复核」。
 * 合同号推导：分段合同号取「取值来源合同」与「当事方条目建立合同」中生效较晚的一份。
 *
 * <p>纯函数：结果与到达顺序无关（链内先按阶段类序 + 生效日 + 签署日 + instanceKey 确定性排序）。
 */
public final class LeaseFoldEngine implements FoldingEngine {

    /** 折叠规则编号（R-1〜R-5 规格命名）。 */
    public static final String R1 = "R-1";
    public static final String R2 = "R-2";
    public static final String R3 = "R-3";
    public static final String R4 = "R-4";
    public static final String R5 = "R-5";

    private final LeasePackConfig config;

    public LeaseFoldEngine(LeasePackConfig config) {
        this.config = config;
    }

    @Override
    public Map<String, Object> fold(String storeKey, List<Map<String, Object>> confirmedChain) {
        List<LeaseModel.Contract> chain = parse(confirmedChain);
        sortDeterministic(chain);

        List<LeaseModel.ReviewItem> reviews = new ArrayList<>();
        List<LeaseModel.Deposit> depositsOut = new ArrayList<>();
        Set<String> flags = new LinkedHashSet<>();

        // ── 阶段 2：结构（当事方建立合同；转让/甲方变更继承 → R-14）──
        Map<String, LeaseModel.Contract> partyContractBySubject = new LinkedHashMap<>();
        for (LeaseModel.Contract c : chain) {
            for (String subject : c.subjectCodes) {
                LeaseModel.Contract prev = partyContractBySubject.get(subject);
                if (prev == null) {
                    partyContractBySubject.put(subject, c);
                } else if (isPartyChange(c.kind) && compare(c, prev) > 0) {
                    partyContractBySubject.put(subject, c);
                    reviews.add(new LeaseModel.ReviewItem("R-14",
                            "转让/甲方变更承接：标的 " + subject + " 术语继承，当事方建立合同 " + c.contractNo));
                }
            }
        }
        LeaseModel.Contract baseline = chain.stream()
                .filter(c -> "主合同".equals(c.kind)).findFirst()
                .orElse(chain.isEmpty() ? null : chain.get(0));
        String termStart = baseline == null ? null : baseline.termStart;
        String termEnd = baseline == null ? null : baseline.termEnd;

        // ── 阶段 1+3：修改声明 → 变更操作；建对象 + 取值（逐分段）──
        // 取值候选：非续约声明按生效日升序 → 分段；续约声明阶段 4 单独补齐
        Map<String, LeaseModel.Contract> contractByInstanceKey = new LinkedHashMap<>();
        for (LeaseModel.Contract c : chain) {
            if (c.instanceKey != null) {
                contractByInstanceKey.put(c.instanceKey, c);
            }
        }
        Map<String, List<LeaseModel.FeeValue>> byFeeKey = new LinkedHashMap<>();
        for (LeaseModel.Contract c : chain) {
            for (LeaseModel.FeeValue v : c.feeValues) {
                if (!v.renewal && !v.removal) {
                    byFeeKey.computeIfAbsent(v.feeKey, k -> new ArrayList<>()).add(v);
                }
            }
        }

        List<LeaseModel.Segment> segments = new ArrayList<>();
        for (Map.Entry<String, List<LeaseModel.FeeValue>> e : byFeeKey.entrySet()) {
            String feeKey = e.getKey();
            String subject = subjectOf(feeKey);
            // 候选分流：金额段只用带金额的候选；减免候选（含只带 abate 的声明）独立择优
            List<LeaseModel.FeeValue> cands = new ArrayList<>(e.getValue().stream()
                    .filter(v -> v.baseAmount != null).toList());
            cands.sort(Comparator
                    .<LeaseModel.FeeValue, String>comparing(v -> nvl(v.effectiveDate))
                    .thenComparing(v -> nvl(v.signDate))
                    .thenComparing(v -> nvl(v.instanceKey)));

            // R-15：跨标的拆分
            if ("ALL".equals(subject) || (subject != null && subject.contains(","))) {
                reviews.add(new LeaseModel.ReviewItem("R-15",
                        "跨标的费用：" + feeKey + " 需拆分口径（N3=" + config.question("N3") + "）"));
            }

            // 逐候选生成分段：[本候选生效日, 下一候选生效日)
            String previousEnd = null;
            Double priorAmount = null;
            LeaseModel.FeeValue priorCand = null;
            for (int i = 0; i < cands.size(); i++) {
                LeaseModel.FeeValue cand = cands.get(i);
                String start = i == 0 ? firstNonBlank(termStart, cand.effectiveDate) : firstNonBlank(cand.effectiveDate, previousEnd);
                boolean temporary = cand.temporary && cand.restoreDate != null;
                String end;
                if (temporary) {
                    end = cand.restoreDate; // R-2：临时变更到期恢复
                } else if (i < cands.size() - 1 && cands.get(i + 1).effectiveDate != null) {
                    end = cands.get(i + 1).effectiveDate; // 下一候选生效日截断
                } else {
                    end = termEnd;
                }
                if (start != null && end != null && start.compareTo(end) >= 0) {
                    continue;
                }
                segments.add(new LeaseModel.Segment(feeKey, start, end, cand.baseAmount, cand.freeMonths,
                        contractNoOf(cand, partyContractBySubject.get(subject), contractByInstanceKey), cand.instanceKey, false));
                if (i > 0) {
                    reviews.add(new LeaseModel.ReviewItem(R3,
                            "专项协议优先：" + feeKey + " 自 " + start + " 由 " + cand.instanceKey + " 在其范围内覆盖"));
                }
                // R-2：恢复段（沿用上一候选取值）
                if (temporary) {
                    String restoreEnd = firstNonBlank(termEnd, null);
                    if (priorCand != null && priorAmount != null) {
                        segments.add(new LeaseModel.Segment(feeKey, cand.restoreDate, restoreEnd,
                                priorAmount, priorCand.freeMonths,
                                contractNoOf(priorCand, partyContractBySubject.get(subject), contractByInstanceKey),
                                priorCand.instanceKey, false));
                    }
                    reviews.add(new LeaseModel.ReviewItem(R2,
                            "临时变更到期恢复：" + feeKey + " 于 " + cand.restoreDate + " 恢复"
                                    + (priorAmount == null ? "" : "前额 " + priorAmount)));
                }
                previousEnd = end;
                priorAmount = cand.baseAmount;
                priorCand = cand;
            }

            // 减免（abate）归属：候选从原始声明全集取（含只带 abate、不带金额的声明）；
            // 优先作用范围更窄（有 abateStart）者；多减免重叠 → R-4；附条件 → R-5
            List<LeaseModel.FeeValue> abateCands = new ArrayList<>(e.getValue().stream()
                    .filter(v -> v.freeMonths != null).toList());
            abateCands.sort(Comparator
                    .<LeaseModel.FeeValue>comparingInt(v -> v.abateStart != null ? 0 : 1)
                    .thenComparing(v -> nvl(v.effectiveDate), Comparator.reverseOrder())
                    .thenComparing(v -> nvl(v.signDate), Comparator.reverseOrder()));
            if (!abateCands.isEmpty()) {
                LeaseModel.FeeValue abatePick = abateCands.get(0);
                String segSource = attachFreeMonths(segments, feeKey, abatePick);
                if (segSource != null && !segSource.equals(abatePick.instanceKey)) {
                    reviews.add(new LeaseModel.ReviewItem(R3,
                            "专项协议优先：" + feeKey + " 减免由 " + abatePick.instanceKey + " 在其范围内覆盖"));
                }
                for (int i = 1; i < abateCands.size(); i++) {
                    if (abateOverlap(abatePick, abateCands.get(i))) {
                        reviews.add(new LeaseModel.ReviewItem(R4,
                                "重叠减免：" + feeKey + " 免租区间重叠（" + abatePick.instanceKey + " 与 "
                                        + abateCands.get(i).instanceKey + "），合并不叠加"));
                    }
                }
                if (abatePick.condition != null && !abatePick.condition.isBlank()) {
                    reviews.add(new LeaseModel.ReviewItem(R5,
                            "附条件减免：" + feeKey + " 条件「" + abatePick.condition + "」进复核"));
                }
            }
        }

        // ── 阶段 4：续约补齐（只填续约期内没被覆盖的部分）──
        for (LeaseModel.Contract c : chain) {
            if (!"续约".equals(c.kind)) {
                continue;
            }
            for (LeaseModel.FeeValue v : c.feeValues) {
                if (v.baseAmount == null && v.freeMonths == null) {
                    continue;
                }
                LeaseModel.Segment prior = segments.stream()
                        .filter(s -> s.feeKey().equals(v.feeKey))
                        .reduce((a, b) -> b).orElse(null);
                Double amount = v.baseAmount;
                Double freeMonths = v.freeMonths != null ? v.freeMonths
                        : (prior != null ? prior.freeMonths() : null); // 补齐未覆盖部分
                String start = firstNonBlank(v.abateStart, c.termStart);
                String end = firstNonBlank(c.termEnd, termEnd);
                segments.add(new LeaseModel.Segment(v.feeKey, start, end, amount, freeMonths,
                        c.contractNo, c.instanceKey, true));
                // R-06 / N1：续约「其他条款不变」时递增是否继续
                if (c.renewInherit != null && config.isReview("N1")) {
                    reviews.add(new LeaseModel.ReviewItem("R-06",
                            "续约「其他条款不变」递增是否继续待定（N1=REVIEW）：" + v.feeKey));
                }
                // R-09：递增基数衔接
                if (prior != null && prior.amount() != null && amount != null
                        && amount.compareTo(prior.amount()) < 0) {
                    reviews.add(new LeaseModel.ReviewItem("R-09",
                            "递增基数衔接：" + v.feeKey + " 续约基额 " + amount + " 低于前段 " + prior.amount() + "（倒挂）"));
                }
                // R-11：空窗期（前段止日次日仍早于续约起日才构成空窗；紧邻衔接不算）
                if (prior != null && prior.end() != null && start != null && hasGap(prior.end(), start)) {
                    reviews.add(new LeaseModel.ReviewItem("R-11",
                            "空窗期：" + v.feeKey + " 前段止 " + prior.end() + " 续约起 " + start
                                    + "（N4=" + config.question("N4") + "）"));
                }
                // R-10：免租跨段
                if (prior != null && prior.freeMonths() != null && freeMonths != null
                        && freeMonths.compareTo(prior.freeMonths()) < 0) {
                    reviews.add(new LeaseModel.ReviewItem("R-10",
                            "免租跨段：" + v.feeKey + " 续约免租 " + freeMonths + " 小于前段 " + prior.freeMonths()));
                }
            }
        }

        // ── 阶段 5：边界（终止、缩短、结束类最后统一应用）──
        List<LeaseModel.Segment> bounded = new ArrayList<>();
        for (LeaseModel.Segment s : segments) {
            String cut = null;
            for (LeaseModel.Contract c : chain) {
                if ("终止".equals(c.kind) && c.termEnd != null
                        && (s.end() == null || c.termEnd.compareTo(s.end()) < 0)) {
                    if (cut == null || c.termEnd.compareTo(cut) < 0) {
                        cut = c.termEnd;
                    }
                }
            }
            bounded.add(cut == null ? s
                    : new LeaseModel.Segment(s.feeKey(), s.start(), cut, s.amount(), s.freeMonths(),
                            s.contractNo(), s.source(), s.renewed()));
        }
        boolean terminated = chain.stream().anyMatch(c -> "终止".equals(c.kind));
        if (terminated) {
            if (segments.stream().anyMatch(LeaseModel.Segment::renewed) && config.isReview("N2")) {
                reviews.add(new LeaseModel.ReviewItem("R-07", "终止时专项协议费用是否结束待定（N2=REVIEW）"));
            }
            // R-12：终止结算未清（押金存在且无退还声明）
            boolean refundDeclared = chain.stream()
                    .filter(c -> "终止".equals(c.kind))
                    .flatMap(c -> c.modifications.stream())
                    .anyMatch(m -> m.type != null && m.type.contains("退还"));
            if (!depositsAll(chain).isEmpty() && !refundDeclared) {
                reviews.add(new LeaseModel.ReviewItem("R-12", "终止结算：押金退还条款未见，结算口径待复核"));
            }
        }

        // R-1 / R-13 / R-16 / R-08
        for (LeaseModel.Contract c : chain) {
            if (c.relocates) {
                flags.add("relocated");
                reviews.add(new LeaseModel.ReviewItem(R1,
                        "迁址重新建档：" + c.contractNo + " 触发档案重建（原档封存）"));
            }
            if (c.hasCrossRefs) {
                reviews.add(new LeaseModel.ReviewItem("R-16",
                        "上游已更新：" + c.contractNo + " 存在跨合同引用，按新版本计算并标记"));
            }
            if ("回购".equals(c.kind)) {
                flags.add("bought-back");
                reviews.add(new LeaseModel.ReviewItem("R-13", "回购清零：" + c.contractNo + " 押金与费用清零"));
            }
        }
        // R-08：押金结转（续约/转让合同携带押金 → 标记结转）
        for (LeaseModel.Contract c : chain) {
            for (LeaseModel.Deposit d : c.deposits) {
                LeaseModel.Deposit out = new LeaseModel.Deposit();
                out.key = d.key;
                out.depositType = d.depositType;
                out.amount = d.amount;
                out.contractNo = c.contractNo;
                out.carried = "续约".equals(c.kind) || isPartyChange(c.kind);
                depositsOut.add(out);
            }
        }
        if (flags.contains("bought-back")) {
            depositsOut.clear(); // R-13：回购清零
        }

        reviews.sort(Comparator.comparing(LeaseModel.ReviewItem::rule)
                .thenComparing(LeaseModel.ReviewItem::detail));
        segments.sort(Comparator.comparing(LeaseModel.Segment::feeKey)
                .thenComparing(s -> nvl(s.start()))
                .thenComparing(s -> nvl(s.contractNo())));

        // ── 输出（Map 签名；reviewItems 由 C-8 FoldScheduler 提取进 ont_state）──
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("storeKey", storeKey);
        out.put("contractCount", chain.size());
        List<Map<String, Object>> contractList = new ArrayList<>();
        for (LeaseModel.Contract c : chain) {
            Map<String, Object> cm = new LinkedHashMap<>();
            cm.put("contractNo", c.contractNo);
            cm.put("kind", c.kind);
            cm.put("effectiveDate", c.effectiveDate);
            cm.put("instanceKey", c.instanceKey);
            contractList.add(cm);
        }
        out.put("contracts", contractList);
        out.put("segments", toSegmentMaps(bounded));
        List<Map<String, Object>> depList = new ArrayList<>();
        for (LeaseModel.Deposit d : depositsOut) {
            Map<String, Object> dm = new LinkedHashMap<>();
            dm.put("key", d.key);
            dm.put("depositType", d.depositType);
            dm.put("amount", d.amount);
            dm.put("contractNo", d.contractNo);
            dm.put("carried", d.carried);
            depList.add(dm);
        }
        out.put("deposits", depList);
        List<Map<String, Object>> reviewList = new ArrayList<>();
        for (LeaseModel.ReviewItem r : reviews) {
            Map<String, Object> rm = new LinkedHashMap<>();
            rm.put("rule", r.rule());
            rm.put("detail", r.detail());
            reviewList.add(rm);
        }
        out.put("reviewItems", reviewList);
        out.put("flags", List.copyOf(flags));
        return out;
    }

    // ── 分段辅助 ─────────────────────────────────────────────

    /** 免租归属到分段；返回被附着分段的来源（instanceKey），无分段可附返回 null。 */
    private static String attachFreeMonths(List<LeaseModel.Segment> segments, String feeKey,
                                           LeaseModel.FeeValue abatePick) {
        for (int i = 0; i < segments.size(); i++) {
            LeaseModel.Segment s = segments.get(i);
            if (!s.feeKey().equals(feeKey)) {
                continue;
            }
            boolean contains = abatePick.abateStart == null
                    || (s.start() != null && abatePick.abateStart.compareTo(s.start()) >= 0)
                    && (s.end() == null || abatePick.abateStart.compareTo(s.end()) < 0);
            if (contains) {
                segments.set(i, new LeaseModel.Segment(s.feeKey(), s.start(), s.end(), s.amount(),
                        abatePick.freeMonths, s.contractNo(), s.source(), s.renewed()));
                return s.source();
            }
        }
        // 未落在任何分段内（如免租晚于租期）→ 附在最后一段
        for (int i = segments.size() - 1; i >= 0; i--) {
            LeaseModel.Segment s = segments.get(i);
            if (s.feeKey().equals(feeKey)) {
                segments.set(i, new LeaseModel.Segment(s.feeKey(), s.start(), s.end(), s.amount(),
                        abatePick.freeMonths, s.contractNo(), s.source(), s.renewed()));
                return s.source();
            }
        }
        return null;
    }

    /** 空窗判定：续约起日晚于「前段止日 + 1 天」才构成空窗；紧邻或重叠不算。日期不可解析时保守报复核。 */
    private static boolean hasGap(String priorEnd, String start) {
        try {
            java.time.LocalDate end = java.time.LocalDate.parse(priorEnd);
            java.time.LocalDate begin = java.time.LocalDate.parse(start);
            return begin.isAfter(end.plusDays(1));
        } catch (RuntimeException e) {
            return !start.equals(priorEnd); // 非 ISO 日期：仅同日视为衔接
        }
    }

    private static boolean abateOverlap(LeaseModel.FeeValue a, LeaseModel.FeeValue b) {
        if (a.abateStart == null || b.abateStart == null) {
            return false; // 未带期间的减免不判区间重叠
        }
        String aEnd = a.abateEnd == null ? "9999-12-31" : a.abateEnd;
        String bEnd = b.abateEnd == null ? "9999-12-31" : b.abateEnd;
        return a.abateStart.compareTo(bEnd) <= 0 && b.abateStart.compareTo(aEnd) <= 0;
    }

    private static List<Map<String, Object>> toSegmentMaps(List<LeaseModel.Segment> segments) {
        List<Map<String, Object>> segList = new ArrayList<>();
        for (LeaseModel.Segment s : segments) {
            Map<String, Object> sm = new LinkedHashMap<>();
            sm.put("feeKey", s.feeKey());
            sm.put("start", s.start());
            sm.put("end", s.end());
            sm.put("amount", s.amount());
            sm.put("freeMonths", s.freeMonths());
            sm.put("contractNo", s.contractNo());
            sm.put("source", s.source());
            sm.put("renewed", s.renewed());
            segList.add(sm);
        }
        return segList;
    }

    // ── 解析（防御式：坏结构按空处理，不抛错）─────────────────

    private List<LeaseModel.Contract> parse(List<Map<String, Object>> chain) {
        List<LeaseModel.Contract> out = new ArrayList<>();
        if (chain == null) {
            return out;
        }
        for (Map<String, Object> m : chain) {
            if (m == null) {
                continue;
            }
            LeaseModel.Contract c = new LeaseModel.Contract();
            c.instanceKey = str(m.get("instanceKey"));
            c.contractNo = str(orDefault(m.get("contractNo"), c.instanceKey));
            c.kind = str(orDefault(m.get("kind"), "主合同"));
            c.signDate = str(m.get("signDate"));
            c.effectiveDate = str(orDefault(m.get("effectiveDate"), m.get("signDate")));
            c.termStart = str(m.get("termStart"));
            c.termEnd = str(m.get("termEnd"));
            c.renewInherit = str(m.get("renewInherit"));
            Object relocate = m.get("relocates");
            c.relocates = relocate instanceof Boolean b && b;
            Object refs = m.get("refs");
            c.hasCrossRefs = refs instanceof List<?> rl && !rl.isEmpty();
            for (Object s : list(m.get("subjects"))) {
                String code = str(objMap(s).get("code"));
                if (code != null) {
                    c.subjectCodes.add(code);
                }
            }
            if (c.subjectCodes.isEmpty()) {
                c.subjectCodes.add("S1");
            }
            for (Object mod : list(m.get("modifications"))) {
                Map<String, Object> mm = objMap(mod);
                LeaseModel.Modification mo = new LeaseModel.Modification();
                mo.type = str(mm.get("type"));
                mo.target = str(mm.get("target"));
                mo.effectiveDate = str(orDefault(mm.get("effectiveDate"), c.effectiveDate));
                mo.condition = str(mm.get("condition"));
                c.modifications.add(mo);
                if ("迁址".equals(mo.type)) {
                    c.relocates = true;
                }
            }
            for (Object f : list(m.get("feeItems"))) {
                Map<String, Object> fm = objMap(f);
                LeaseModel.FeeValue v = new LeaseModel.FeeValue();
                v.feeKey = str(orDefault(fm.get("feeKey"), "RENT@S1"));
                v.baseAmount = num(fm.get("baseAmount"));
                v.effectiveDate = str(orDefault(fm.get("effectiveDate"), c.effectiveDate));
                v.signDate = c.signDate;
                v.instanceKey = c.instanceKey;
                v.temporary = c.temporary;
                v.restoreDate = c.restoreDate;
                Map<String, Object> ab = objMap(fm.get("abate"));
                v.freeMonths = num(ab.get("freeMonths"));
                v.abateStart = str(ab.get("start"));
                v.abateEnd = str(ab.get("end"));
                v.condition = str(ab.get("condition"));
                Object renewal = fm.get("renewal");
                v.renewal = renewal instanceof Boolean b && b;
                Object removal = fm.get("removal");
                v.removal = removal instanceof Boolean b && b;
                c.feeValues.add(v);
            }
            for (Object d : list(m.get("deposits"))) {
                Map<String, Object> dm = objMap(d);
                LeaseModel.Deposit dep = new LeaseModel.Deposit();
                dep.key = str(orDefault(dm.get("key"), "保证金@S1"));
                dep.depositType = str(dm.get("depositType"));
                dep.amount = num(dm.get("amount"));
                dep.contractNo = c.contractNo;
                c.deposits.add(dep);
            }
            // 临时变更标记：合同级 temporary + 恢复日透传到费用取值
            Object temporary = m.get("temporary");
            c.temporary = temporary instanceof Boolean b && b;
            c.restoreDate = str(m.get("restoreDate"));
            for (LeaseModel.FeeValue v : c.feeValues) {
                v.temporary = c.temporary;
                v.restoreDate = c.restoreDate;
            }
            out.add(c);
        }
        return out;
    }

    /** 确定性排序：阶段类序（主合同 0 → 续约/变更/补充 1 → 转让/甲方变更 2 → 终止/回购 3）
     *  → 生效日 → 签署日 → instanceKey。 */
    private void sortDeterministic(List<LeaseModel.Contract> chain) {
        for (int i = 0; i < chain.size(); i++) {
            chain.get(i).docOrder = i;
        }
        chain.sort(Comparator
                .<LeaseModel.Contract>comparingInt(c -> phaseOf(c.kind))
                .thenComparing(c -> nvl(c.effectiveDate))
                .thenComparing(c -> nvl(c.signDate))
                .thenComparing(c -> nvl(c.instanceKey)));
    }

    private static int phaseOf(String kind) {
        if (kind == null) {
            return 1;
        }
        return switch (kind) {
            case "主合同" -> 0;
            case "终止", "回购" -> 3;
            case "转让", "甲方变更" -> 2;
            default -> 1;
        };
    }

    /** 合同号推导：取值来源合同 与 当事方条目建立合同 中生效较晚的一份。 */
    private String contractNoOf(LeaseModel.FeeValue valueCand, LeaseModel.Contract partyContract,
                                Map<String, LeaseModel.Contract> contractByInstanceKey) {
        LeaseModel.Contract valueContract = contractByInstanceKey.get(valueCand.instanceKey);
        String valueContractNo = valueContract == null ? valueCand.instanceKey : valueContract.contractNo;
        if (partyContract == null) {
            return valueContractNo;
        }
        return nvl(partyContract.effectiveDate).compareTo(nvl(valueCand.effectiveDate)) > 0
                ? partyContract.contractNo : valueContractNo;
    }

    /** 生效较晚比较：生效日 → 签署日 → instanceKey。 */
    private static int compare(LeaseModel.Contract a, LeaseModel.Contract b) {
        int c = nvl(a.effectiveDate).compareTo(nvl(b.effectiveDate));
        if (c != 0) {
            return c;
        }
        c = nvl(a.signDate).compareTo(nvl(b.signDate));
        if (c != 0) {
            return c;
        }
        return nvl(a.instanceKey).compareTo(nvl(b.instanceKey));
    }

    private static boolean isPartyChange(String kind) {
        return "转让".equals(kind) || "甲方变更".equals(kind);
    }

    private static List<LeaseModel.Deposit> depositsAll(List<LeaseModel.Contract> chain) {
        List<LeaseModel.Deposit> out = new ArrayList<>();
        for (LeaseModel.Contract c : chain) {
            out.addAll(c.deposits);
        }
        return out;
    }

    private static String subjectOf(String feeKey) {
        int at = feeKey.indexOf('@');
        return at > 0 ? feeKey.substring(at + 1) : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> objMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : Map.of();
    }

    private static List<Object> list(Object o) {
        return o instanceof List ? (List<Object>) o : List.of();
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static Double num(Object o) {
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        if (o instanceof String s && !s.isBlank()) {
            try {
                return Double.valueOf(s.replace(",", ""));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static Object orDefault(Object v, Object dft) {
        return v == null ? dft : v;
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }
}
