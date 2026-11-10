package com.tioka.governance.application.autonomy;

/**
 * 事后抽检采样器（ADR-034「事后抽检」）：L3/L4 自动提交动作按比例抽样（默认 10%）。
 * 生产实现按 actionId 稳定哈希取样（同动作多次登记结果一致）；测试注入固定实现离线测。
 */
public interface SamplingPolicy {

    boolean isSampled(String agentId, String actionClass, String actionId);

    /** 按前缀哈希的稳定采样：确定性，无随机数依赖。 */
    final class Percentage implements SamplingPolicy {

        private final int percent;

        public Percentage(int percent) {
            this.percent = Math.max(0, Math.min(100, percent));
        }

        @Override
        public boolean isSampled(String agentId, String actionClass, String actionId) {
            String key = (agentId == null ? "" : agentId) + ":" + (actionClass == null ? "" : actionClass)
                    + ":" + (actionId == null ? "" : actionId);
            int h = Math.abs(key.hashCode());
            return h % 100 < percent;
        }
    }

    /** 固定返回值（测试用）。 */
    final class Fixed implements SamplingPolicy {

        private final boolean value;

        public Fixed(boolean value) {
            this.value = value;
        }

        @Override
        public boolean isSampled(String agentId, String actionClass, String actionId) {
            return value;
        }
    }
}
