package tioka.pack.lease.fold;

import java.util.ArrayList;
import java.util.List;

/**
 * 租约状态 typed 内部模型（LSE-03 裁决①：FoldingEngine 对外保持 Map 签名，
 * 引擎内部自持类型化域模型，fold 出口统一转 Map）。包私有，纯数据。
 */
final class LeaseModel {

    /** 合同（确认版本）的领域视图。 */
    static final class Contract {
        String instanceKey;
        String contractNo;
        String kind;            // 主合同/补充/续约/变更/终止/转让/回购/甲方变更
        String signDate;        // yyyy-MM-dd（比较用字符串字典序即时间序）
        String effectiveDate;
        String termStart;
        String termEnd;
        int docOrder;           // 确定性排序的最终序（取值优先级第 4 位）
        List<Modification> modifications = new ArrayList<>();
        List<FeeValue> feeValues = new ArrayList<>();   // 本合同声明的费用取值
        List<Deposit> deposits = new ArrayList<>();
        List<String> subjectCodes = new ArrayList<>();
        boolean relocates;      // 迁址（R-1）
        boolean temporary;      // 临时变更（R-2）
        String restoreDate;     // 临时变更恢复日
        String renewInherit;    // 续约「其他条款不变」声明（N1）
        boolean hasCrossRefs;   // 跨合同引用（R-16）
    }

    /** 修改声明 → 变更操作（阶段 1）。 */
    static final class Modification {
        String type;        // 词表：调价/免租调整/新增费用/终止费用/主体变更/提前终止/迁址…
        String target;      // 目标费用键（RENT@S1）或标的
        String value;       // 新内容（金额等）
        String startDate;
        String endDate;
        String effectiveDate;
        String condition;   // 附条件（R-5）
    }

    /** 费用项取值（阶段 2/3 输入；生效/签署/来源随合同透传，供取值优先级与合同号推导）。 */
    static final class FeeValue {
        String feeKey;      // RENT@S1
        Double baseAmount;  // 元/月
        Double freeMonths;
        String abateStart;
        String abateEnd;
        String condition;   // 附条件减免（R-5）
        boolean renewal;
        boolean removal;    // 终止费用
        String effectiveDate;
        String signDate;
        String instanceKey; // 取值来源合同（instanceKey 即来源标识）
        boolean temporary;  // 临时变更（R-2）
        String restoreDate;
    }

    /** 押金。 */
    static final class Deposit {
        String key;
        String depositType;
        Double amount;
        String contractNo;
        boolean carried;    // R-08 结转
    }

    /** 折叠后的费用分段（输出 segments）。 */
    record Segment(String feeKey, String start, String end, Double amount, Double freeMonths,
                   String contractNo, String source, boolean renewed) {
    }

    /** 复核项（输出 reviewItems；C-8 FoldScheduler 提取进 ont_state.review_items）。 */
    record ReviewItem(String rule, String detail) {
    }
}
