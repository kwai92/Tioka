package com.tioka.ontology.domain.cel;

import dev.cel.common.CelFunctionDecl;
import dev.cel.common.CelOverloadDecl;
import dev.cel.common.types.SimpleType;
import dev.cel.runtime.CelRuntime;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 平台内置 CEL 函数（ADR-036 L168 清单）：{@code status(x)、isKnown(x)、months(period)、
 * segments(item)、sum(list)、annualRate(segments)}。领域包可再注册更多（见 DomainPack.celFunctions）。
 *
 * <p>语义（本批口径，租赁语义随 M3-04 领域包复核）：
 * <ul>
 *   <li>{@code isKnown(x)}：x 为 optional（未知叶子）→ false；null → false；其余 → true。</li>
 *   <li>{@code status(x)}：optional → "UNKNOWN"/"KNOWN"；其余值 → "KNOWN"。
 *       「空白 / 不适用」细分在 requires 路径处理（求值器 Java 侧），CEL 内统一 none。</li>
 *   <li>{@code months(period)}：period map 的 {@code years}*12 + {@code months}（缺省 0）。</li>
 *   <li>{@code segments(item)}：取 item 的 {@code segments}（缺省 {@code sched.segments}）列表。</li>
 *   <li>{@code sum(list)}：数值求和（double）。</li>
 *   <li>{@code annualRate(segments)}：按 years 加权平均 {@code rate}（无 years 视为 1）。</li>
 * </ul>
 *
 * <p>声明统一 (dyn) → r 全局重载（cel-java 0.9.1 探针实证：optional(dyn) 与 dyn 双重载编译期
 * 判重叠）；运行期单 Object.class 绑定兜底分派（同 overloadId 多绑定仅首个生效）。整数统一 Long。
 */
public final class PlatformCelFunctions {

    private PlatformCelFunctions() {
    }

    public static List<CelFunctionDecl> declarations() {
        return List.of(
                decl("isKnown", "tioka_isKnown", SimpleType.BOOL),
                decl("status", "tioka_status", SimpleType.STRING),
                decl("months", "tioka_months", SimpleType.INT),
                decl("segments", "tioka_segments", SimpleType.DYN),
                decl("sum", "tioka_sum", SimpleType.DOUBLE),
                decl("annualRate", "tioka_annualRate", SimpleType.DOUBLE));
    }

    private static CelFunctionDecl decl(String name, String overloadId, dev.cel.common.types.CelType result) {
        return CelFunctionDecl.newFunctionDeclaration(
                name, CelOverloadDecl.newGlobalOverload(overloadId, result, SimpleType.DYN));
    }

    public static List<CelRuntime.CelFunctionBinding> bindings() {
        List<CelRuntime.CelFunctionBinding> out = new java.util.ArrayList<>();
        // cel-java 0.9.1 探针实证：同一 overloadId 注册多个绑定仅第一个生效（first-wins），
        // 故 isKnown / status 以单个 Object.class 绑定兜底——运行期 canHandle 对
        // Object.isAssignableFrom(参数实际类) 恒真，Long / Map / Optional 等全部分派到同一实现，
        // 由实现内部判型（optional → false/UNKNOWN）。
        out.add(CelRuntime.CelFunctionBinding.from("tioka_isKnown", Object.class, PlatformCelFunctions::isKnown));
        out.add(CelRuntime.CelFunctionBinding.from("tioka_status", Object.class, PlatformCelFunctions::status));
        out.add(CelRuntime.CelFunctionBinding.from("tioka_months", Map.class, PlatformCelFunctions::months));
        out.add(CelRuntime.CelFunctionBinding.from("tioka_segments", Map.class, PlatformCelFunctions::segments));
        // sum / annualRate：实参既可能是字面量 list（ImmutableListValue→List），也可能是 m 里的
        // 数组（ModelValueProvider 把数组转为 identity 键 map）——Object 绑定 + 实现内归一
        out.add(CelRuntime.CelFunctionBinding.from("tioka_sum", Object.class, PlatformCelFunctions::sum));
        out.add(CelRuntime.CelFunctionBinding.from("tioka_annualRate", Object.class, PlatformCelFunctions::annualRate));
        return out;
    }

    private static boolean isKnown(Object x) {
        return x != null && !(x instanceof Optional<?> o && o.isEmpty());
    }

    private static String status(Object x) {
        if (x == null) {
            return "UNKNOWN";
        }
        if (x instanceof Optional<?> o) {
            return o.isPresent() ? "KNOWN" : "UNKNOWN";
        }
        return "KNOWN";
    }

    private static long months(Object period) {
        Map<?, ?> p = (Map<?, ?>) period;
        long years = numberOf(p.get("years"), 0L).longValue();
        long ms = numberOf(p.get("months"), 0L).longValue();
        return years * 12 + ms;
    }

    private static Object segments(Object item) {
        Map<?, ?> i = (Map<?, ?>) item;
        Object segs = i.get("segments");
        if (segs == null) {
            Object sched = i.get("sched");
            if (sched instanceof Map<?, ?> schedMap) {
                segs = schedMap.get("segments");
            }
        }
        return segs == null ? List.of() : segs;
    }

    /** 列表实参归一：字面量 list 直接用；m 里的数组是 identity 键 map → 取 values（保持顺序）。 */
    private static java.util.Collection<?> listOf(Object arg) {
        if (arg instanceof List<?> l) {
            return l;
        }
        if (arg instanceof Map<?, ?> m) {
            return m.values();
        }
        return List.of();
    }

    private static double sum(Object list) {
        double total = 0d;
        for (Object item : listOf(list)) {
            if (item instanceof Number n) {
                total += n.doubleValue();
            }
        }
        return total;
    }

    private static double annualRate(Object list) {
        double weighted = 0d;
        double years = 0d;
        for (Object item : listOf(list)) {
            if (item instanceof Map<?, ?> seg) {
                double rate = numberOf(seg.get("rate"), 0d).doubleValue();
                double y = numberOf(seg.get("years"), 1L).doubleValue();
                weighted += rate * y;
                years += y;
            }
        }
        return years == 0d ? 0d : weighted / years;
    }

    private static Number numberOf(Object v, Number fallback) {
        if (v instanceof Number n) {
            return n;
        }
        return fallback;
    }
}
