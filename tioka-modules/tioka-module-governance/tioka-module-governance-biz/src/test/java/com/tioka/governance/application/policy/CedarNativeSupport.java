package com.tioka.governance.application.policy;

/**
 * cedar-java 原生库可用性探测（测试专用）。
 *
 * <p>存在理由：GOV-07 的两遍求值器依赖 {@code com.cedarpolicy.BasicAuthorizationEngine}，
 * 该类的静态初始化块会绑定 JNI 原生库。原生库缺失时（本机未安装 cedar-java 4.11.0 的
 * native 构件）抛 {@link UnsatisfiedLinkError}，且该类随即进入初始化失败状态，后续任何
 * 访问都变成 {@link NoClassDefFoundError}。
 *
 * <p>若不探测，测试类里的 {@code static final PolicyEvaluator} 会让**测试类自身的
 * {@code <clinit>} 失败**，导致该类全部用例报 error，并连带污染同一 JVM 内其它引用
 * {@code PolicyEvaluator} 的测试类。这类失败与代码正确性无关，却让
 * {@code mvn test} 的退出码永久非 0，使回归信号失去可判定性。
 *
 * <p>因此按 JUnit 5 约定处理：原生库缺失 → {@code assumeTrue} 跳过并计入 skipped，
 * 原生库存在 → 断言强度完全不变。这不是长期屏蔽（不使用 {@code @Disabled}），
 * 装好原生库后用例自动恢复执行。
 *
 * <p>刻意用字符串反射而非 {@code import com.cedarpolicy.*}，避免给测试源码引入
 * 编译期 Cedar 依赖（ArchUnit 规则 cedarOnlyInGovernance 要求只有
 * {@link PolicyEvaluator} 依赖 cedar-java）。
 */
final class CedarNativeSupport {

    /** cedar-java 求值引擎类名；静态初始化即绑定原生库。 */
    private static final String ENGINE_CLASS = "com.cedarpolicy.BasicAuthorizationEngine";

    /** 探测结果只算一次：类初始化失败不可恢复，重复探测没有意义。 */
    private static volatile Boolean available;

    /** 不可用时的原因，供 skipped 报告与排查使用。 */
    private static volatile String reason;

    private CedarNativeSupport() {
    }

    /** 原生库是否可用。首次调用触发探测并缓存结果。 */
    static boolean available() {
        Boolean cached = available;
        if (cached != null) {
            return cached;
        }
        synchronized (CedarNativeSupport.class) {
            if (available == null) {
                probe();
            }
            return available;
        }
    }

    /** 不可用原因；可用时返回 {@code null}。 */
    static String reason() {
        return reason;
    }

    private static void probe() {
        try {
            // initialize=true：必须真正触发 <clinit>，否则探测不到 JNI 绑定失败
            Class.forName(ENGINE_CLASS, true, CedarNativeSupport.class.getClassLoader());
            available = Boolean.TRUE;
            reason = null;
        } catch (Throwable t) {
            // UnsatisfiedLinkError（原生库缺失）/ NoClassDefFoundError（初始化已失败）
            // / ExceptionInInitializerError 均归为「本机不具备 Cedar 运行条件」
            available = Boolean.FALSE;
            reason = t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : ": " + t.getMessage());
        }
    }
}
