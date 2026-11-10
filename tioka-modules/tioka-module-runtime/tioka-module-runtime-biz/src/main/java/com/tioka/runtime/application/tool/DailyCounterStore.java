package com.tioka.runtime.application.tool;

import java.time.LocalDate;
import java.util.List;

/**
 * TRU-02 当日累计（M3-05 §3.3 / ADR-032「编译成 Cedar」注记）：Redis 键按（Agent、动作类别、日期）
 * 组织，{@code commit} 成功后加上变更单汇总；撤销扣回归 TRU-07（本批不做）。
 *
 * <p>日期按空间时区切分（默认 Asia/Shanghai，存储仍为 UTC）。Redis 数据丢失时从当天
 * {@code trust_action} 中 APPLIED 且未撤销的记录重算；重算期间该 Agent 的超额判断按
 * 「已用满」处理（宁可多转审批，M3-05 §3.3）。测试 mock Redis，禁止直连。
 */
public interface DailyCounterStore {

    /**
     * commit 成功后累加变更单汇总。异常不阻断 commit（计数缺失由重算兜底）。
     *
     * @return 累加后的当日用量
     */
    DailyUsage add(String agentId, String actionClass, LocalDate day,
                   long amountMicros, long records);

    /**
     * 撤销成功后扣回当日累计（TRU-07 补偿链，明确语义：减法而非负数 add）。
     * 扣减下限为 0（不出现负累计）；异常不阻断补偿主链（Redis 丢失由重算兜底，
     * 重算口径为「APPLIED 且未撤销」，COMPENSATED 动作天然不计入）。
     *
     * @return 扣回后的当日用量
     */
    DailyUsage deduct(String agentId, String actionClass, LocalDate day,
                      long amountMicros, long records);

    /** 读当日用量；Redis 不可用时返回 {@code DailyUsage.unknownUsage()}（按已用满处理）。 */
    DailyUsage get(String agentId, String actionClass, LocalDate day);

    /** Redis 丢失重算：扫描当天 APPLIED 且未撤销的 trust_action 汇总后回填。 */
    DailyUsage rebuild(String agentId, String actionClass, LocalDate day);

    /** 当日用量快照。unknown = Redis 不可用/重算中，超额判断按「已用满」。 */
    record DailyUsage(long amountMicros, long records, boolean unknown) {

        public static DailyUsage zero() {
            return new DailyUsage(0, 0, false);
        }

        /** 工厂：Redis 不可用 / 重算中（注意与组件访问器 unknown() 区分）。 */
        public static DailyUsage unknownUsage() {
            return new DailyUsage(Long.MAX_VALUE / 4, Long.MAX_VALUE / 4, true);
        }
    }

    /** 重算数据源：trust_action 汇总（infrastructure 提供）。 */
    interface AppliedActionsSource {
        /** 返回当天 APPLIED 且未撤销动作的（金额、记录数）汇总。 */
        List<AppliedAction> list(String agentId, String actionClass, LocalDate dayUtc);

        record AppliedAction(long amountMicros, long records) {}
    }
}
