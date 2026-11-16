package com.tioka.framework.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Foundation 中台授权快照权限项到 Tioka 权限码的显式映射（能力归属清单「权限码映射」）。
 *
 * <p>两条体系语义不同，不能直接混用：Tioka 的 {@code *} 是超管、{@code agent:*} 是通配；
 * 而中台授权快照只做精确匹配。若把中台返回的 {@code *} 原样灌进当前主体的权限集，
 * 会静默把普通用户提升为超管。因此本类执行三条不可放宽的规则：
 *
 * <ol>
 *   <li><b>通配不提升</b>：任何含 {@code *} 的中台权限项一律丢弃，不转成 Tioka 通配码；</li>
 *   <li><b>未知码忽略</b>：未登记在契约 {@code tiokaPermissionCodes} 中的码不授任何权限；</li>
 *   <li><b>超管需显式授权</b>：只有契约 {@code superAdminCodes} 精确命中才授予 Tioka 超管，
 *       默认该集合为空，即中台无法直接授予 Tioka 全量权限。</li>
 * </ol>
 *
 * <p>契约唯一数据源是 classpath 上的 {@code contracts/permission-code-mapping-v1.json}，
 * 由 {@code scripts/verify-permission-code-mapping.mjs} 校验它与 {@link Permissions#ALL_CONCRETE}
 * 逐字一致。加载失败或契约被改成宽松策略时直接抛异常，不允许以空映射静默运行。
 */
public final class PermissionCodeMapper {

    private static final String RESOURCE = "contracts/permission-code-mapping-v1.json";

    private final Set<String> tiokaCodes;
    private final Map<String, String> aliases;
    private final Set<String> superAdminCodes;

    private PermissionCodeMapper(Set<String> tiokaCodes,
                                 Map<String, String> aliases,
                                 Set<String> superAdminCodes) {
        this.tiokaCodes = Set.copyOf(tiokaCodes);
        this.aliases = Map.copyOf(aliases);
        this.superAdminCodes = Set.copyOf(superAdminCodes);
    }

    /**
     * 从 classpath 契约加载；契约缺失、格式非法或策略被放宽时 fail-fast。
     *
     * @param objectMapper 由 Spring 注入的统一 mapper（ENG-05：生产代码不得自行构造）
     */
    public static PermissionCodeMapper loadDefault(ObjectMapper objectMapper) {
        if (objectMapper == null) {
            throw new IllegalArgumentException("ObjectMapper is required (ENG-05)");
        }
        try (InputStream in = PermissionCodeMapper.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("权限码映射契约缺失：" + RESOURCE);
            }
            return fromContract(objectMapper.readTree(in));
        } catch (IOException ex) {
            throw new IllegalStateException("解析权限码映射契约失败：" + RESOURCE, ex);
        }
    }

    /** 包内可见：供契约测试直接验证「放宽策略或登记脏数据时必须拒绝」。 */
    static PermissionCodeMapper fromContract(JsonNode root) {
        require(root.path("version").asInt(-1) == 1, "契约版本必须为 1");
        require("deny-elevation".equals(root.path("wildcardPolicy").asText()),
                "wildcardPolicy 必须为 deny-elevation，禁止放宽为直通");
        require("ignore".equals(root.path("unknownCodePolicy").asText()),
                "unknownCodePolicy 必须为 ignore");
        require("deny-unregistered".equals(root.path("requiredCodePolicy").asText()),
                "requiredCodePolicy 必须为 deny-unregistered");

        Set<String> codes = new LinkedHashSet<>();
        JsonNode codesNode = root.path("tiokaPermissionCodes");
        require(codesNode.isArray() && !codesNode.isEmpty(), "权限码目录不得为空");
        for (JsonNode node : codesNode) {
            String code = node.asText("").trim();
            require(!code.isEmpty() && !isWildcard(code), "权限码目录存在空白或通配项：" + code);
            require(codes.add(code), "权限码目录存在重复项：" + code);
        }

        Map<String, String> aliases = new LinkedHashMap<>();
        JsonNode aliasesNode = root.path("aliases");
        if (aliasesNode.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = aliasesNode.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                String from = entry.getKey().trim();
                String to = entry.getValue().asText("").trim();
                require(!from.isEmpty() && !isWildcard(from), "别名源不得为空白或通配：" + from);
                require(codes.contains(to) && !isWildcard(to), "别名目标必须是已登记的具体权限码：" + to);
                aliases.put(from, to);
            }
        }

        Set<String> superAdmins = new LinkedHashSet<>();
        JsonNode superNode = root.path("superAdminCodes");
        if (superNode.isArray()) {
            for (JsonNode node : superNode) {
                String code = node.asText("").trim();
                require(!code.isEmpty() && !isWildcard(code), "超管码不得为空白或通配：" + code);
                superAdmins.add(code);
            }
        }
    return new PermissionCodeMapper(codes, aliases, superAdmins);
    }

    /** 把中台权限项转换成 Tioka 具体权限码；通配丢弃、未知忽略，绝不产生 {@code *}。 */
    public Set<String> map(Collection<String> foundationCodes) {
        if (foundationCodes == null || foundationCodes.isEmpty()) {
            return Set.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String raw : foundationCodes) {
            if (raw == null) {
                continue;
            }
            String code = raw.trim();
            if (code.isEmpty() || isWildcard(code)) {
                continue;
            }
            String translated = aliases.getOrDefault(code, code);
            if (tiokaCodes.contains(translated)) {
                out.add(translated);
            }
        }
        return Set.copyOf(out);
    }

    /** 仅当契约显式列出的超管码精确命中时才返回 true；中台 {@code *} 永远不算。 */
    public boolean grantsSuperAdmin(Collection<String> foundationCodes) {
        if (superAdminCodes.isEmpty() || foundationCodes == null) {
            return false;
        }
        for (String raw : foundationCodes) {
            if (raw == null) {
                continue;
            }
            String code = raw.trim();
            if (!code.isEmpty() && !isWildcard(code) && superAdminCodes.contains(code)) {
                return true;
            }
        }
        return false;
    }

    /** 声明的权限码是否已登记（供 foundation 模式拒绝拼错的 required 码）。 */
    public boolean isRegistered(String code) {
        return code != null && tiokaCodes.contains(code.trim());
    }

    /** 契约登记的全部具体权限码，不可变。 */
    public Set<String> registeredCodes() {
        return tiokaCodes;
    }

    private static boolean isWildcard(String code) {
        return code.indexOf('*') >= 0;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException("权限码映射契约非法：" + message);
        }
    }
}
