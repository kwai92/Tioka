package tioka.pack.lease;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * 租赁领域包配置（M3-04 §1：V05 金额正则 + 换算表；LSE-03 [?] 业务问题缺省「进复核」）。
 * 资源 {@code config/lease-pack.properties}，JDK 原生 Properties 读取（隔离类加载器内可用）。
 * 纯 JDK，无第三方依赖（domain-pack 权限仅 cpu）。
 */
public final class LeasePackConfig {

    /** 类路径资源位置。 */
    static final String RESOURCE = "/config/lease-pack.properties";

    private final Properties props;

    public LeasePackConfig() {
        this(RESOURCE);
    }

    /** 测试与定制入口：允许指定其他资源路径。 */
    public LeasePackConfig(String resourcePath) {
        this.props = new Properties();
        try (InputStream in = LeasePackConfig.class.getResourceAsStream(resourcePath)) {
            if (in != null) {
                this.props.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            // 配置缺省可用：加载失败按空配置降级（所有 [?] 缺省进复核）
        }
    }

    /** V05：阿拉伯数字金额正则。 */
    public String amountRegex() {
        return props.getProperty("regex.amount", "[0-9][0-9,]*(?:\\.[0-9]+)?");
    }

    /** V05：中文大写金额正则。 */
    public String amountCnRegex() {
        return props.getProperty("regex.amountCn", "[零壹贰叁肆伍陆柒捌玖拾佰仟万亿万元角分整]+");
    }

    /** V04：面积单位正则（引文须含其一）。 */
    public String areaUnitRegex() {
        return props.getProperty("regex.areaUnit", "(?:㎡|平方米|平米|m2|M2)");
    }

    /** V05：单位换算乘数（相对「元」），未知单位返回 null。 */
    public Double unitMultiplier(String unit) {
        String v = props.getProperty("convert.unit." + unit);
        return v == null ? null : Double.valueOf(v);
    }

    /** V05：期间换算月数（相对「月」），未知期间返回 null。 */
    public Double periodMonths(String period) {
        String v = props.getProperty("convert.period." + period);
        return v == null ? null : Double.valueOf(v);
    }

    /**
     * [?] 业务问题口径：{@code question.N1} 等。缺省 {@code REVIEW}（进复核）；
     * 业务确认后改配置，不改代码（M3-04 §1 LSE-03）。
     */
    public String question(String id) {
        return props.getProperty("question." + id, "REVIEW");
    }

    /** 问题答案是否为「进复核」。 */
    public boolean isReview(String id) {
        return "REVIEW".equalsIgnoreCase(question(id));
    }
}
