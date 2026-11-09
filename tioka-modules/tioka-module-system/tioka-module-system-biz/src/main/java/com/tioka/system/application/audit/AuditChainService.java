package com.tioka.system.application.audit;

import com.tioka.framework.id.Ids;
import com.tioka.system.infrastructure.AuditEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * GOV-09 审计哈希链封印与校验（ADR-025 §审计防篡改与外送）。
 *
 * <p>设计口径（遵循 ADR-025 原文）：
 * <ul>
 *   <li><b>写入时不算哈希</b>——避免审计写入被哈希计算串行化；封印由本服务异步完成。</li>
 *   <li><b>按 ID 顺序封印</b>——{@code Ids.newId} 为 Monotonic ULID，按 id 排序即时间序。</li>
 *   <li><b>算法</b>——{@code hash = sha256(prev_hash + '\n' + 规范化后的记录)}，链首 prev_hash 取空串。</li>
 *   <li><b>每租户一条链</b>——workspace_id 为空的系统级记录归入 {@value #SYSTEM_CHAIN} 链。</li>
 *   <li><b>封印锚</b>——每批链末哈希 upsert 进 {@code audit_seal}，可外送 SIEM 作外部锚点。</li>
 *   <li><b>校验接口</b>——{@link #verify(String)} 重算整条链并与落库值、封印锚比对。</li>
 * </ul>
 *
 * <p>多实例互斥：Redis SETNX 轮询锁（TTL 短于调度间隔，失败自动过期）。
 * 每链封印放在独立事务里执行（哈希回写 + 锚 upsert 同生共死），进程崩溃或并发写入
 * 冲突时整链回滚、锚不动，下次运行从锚续链，保证 crash-safe 不断链。
 */
@Component
public class AuditChainService {
    private static final Logger log = LoggerFactory.getLogger(AuditChainService.class);

    /** workspace_id 为空的系统级记录所归属的链名（与 V219 迁移注释、Mapper 口径一致）。 */
    public static final String SYSTEM_CHAIN = "_system";

    /** 封印锁键（多实例互斥）。 */
    static final String LOCK_KEY = "audit:seal:lock";
    /** 锁 TTL：短于调度间隔，实例崩溃后下一轮可自动接管。 */
    static final Duration LOCK_TTL = Duration.ofSeconds(55);
    /** 单轮封印批次上限。 */
    static final int DEFAULT_BATCH = 500;

    /** 规范化字段固定顺序（与 audit_event 列序、审计写入行字段序一致；null→空串）。 */
    static final String[] CANONICAL_FIELDS = {
            "id", "ts", "workspaceId", "actorType", "actorId", "action",
            "resourceType", "resourceId", "outcome", "reason",
            "traceId", "spanId", "service", "requestId", "subject", "clientIp", "detailsJson"
    };

    private final AuditEventRepository repository;
    private final TransactionTemplate tx;
    private final StringRedisTemplate redis;
    /** 封印锁持有者标识（进程内唯一即可，用于安全释放自己持有的锁）。 */
    private final String lockHolder = Ids.newId("seal");

    public AuditChainService(AuditEventRepository repository, PlatformTransactionManager txManager,
                             StringRedisTemplate redis) {
        this.repository = repository;
        this.tx = new TransactionTemplate(txManager);
        this.redis = redis;
    }

    /** 封印轮次结果（供调度日志与运维观测）。 */
    public sealed interface SealOutcome {
        /** 未取得锁：另一实例正在封印。 */
        record SkippedByLock() implements SealOutcome {}
        /** 无待封印记录。 */
        record Empty() implements SealOutcome {}
        /** 本轮封印了 {@code events} 条、覆盖 {@code chains} 条链。 */
        record Sealed(int events, int chains) implements SealOutcome {}
    }

    /**
     * 每分钟封印一轮（ADR-025 口径）。失败只记日志，不影响审计写入主链路。
     */
    @Scheduled(fixedDelay = 60_000)
    public void sealScheduled() {
        try {
            sealPending(DEFAULT_BATCH);
        } catch (RuntimeException e) {
            log.error("audit chain seal round failed", e);
        }
    }

    /**
     * 封印一轮未落哈希的审计记录。调用方也可手动触发（运维补跑）。
     */
    public SealOutcome sealPending(int limit) {
        if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(LOCK_KEY, lockHolder, LOCK_TTL))) {
            log.debug("audit chain seal skipped: another instance holds the lock");
            return new SealOutcome.SkippedByLock();
        }
        try {
            List<Map<String, Object>> rows = repository.listUnsealed(limit);
            if (rows.isEmpty()) {
                return new SealOutcome.Empty();
            }
            Map<String, List<Map<String, Object>>> chains = groupByChain(rows);
            int events = 0;
            for (Map.Entry<String, List<Map<String, Object>>> chain : chains.entrySet()) {
                events += sealChain(chain.getKey(), chain.getValue());
            }
            log.info("audit chain sealed: events={} chains={}", events, chains.size());
            return new SealOutcome.Sealed(events, chains.size());
        } finally {
            releaseLock();
        }
    }

    /**
     * 校验结果：{@code intact=true} 表示链与锚全部一致；断裂时给出首个断点定位。
     */
    public record ChainVerifyResult(
            String workspaceKey,
            int checked,
            boolean intact,
            String brokenAtId,
            String expectedHash,
            String actualHash,
            String anchorHash,
            boolean anchorMatch,
            String summary) {}

    /**
     * 重算指定链并校验：逐条比对 prev_hash 链序与 hash 值，最后比对封印锚。
     *
     * @param workspaceKey 租户 workspace_id，或 {@value #SYSTEM_CHAIN} 表示系统级链
     */
    public ChainVerifyResult verify(String workspaceKey) {
        String key = chainKey(workspaceKey);
        List<Map<String, Object>> rows = repository.listSealedByChain(key);
        Map<String, Object> anchor = repository.getSeal(key);

        String prev = "";
        for (Map<String, Object> row : rows) {
            String id = str(row.get("id"));
            String rowPrev = orEmpty(row.get("prevHash"));
            if (!Objects.equals(rowPrev, prev)) {
                return broken(key, rows.size(), id, prev, rowPrev,
                        "链序断裂：期望 prev_hash 与记录实际 prev_hash 不一致");
            }
            String expected = sha256Hex(prev + "\n" + canonical(row));
            String actual = str(row.get("hash"));
            if (!expected.equals(actual)) {
                return broken(key, rows.size(), id, expected, actual, "哈希不匹配：记录内容疑似被篡改");
            }
            prev = actual;
        }

        String anchorHash = anchor == null ? null : str(anchor.get("lastHash"));
        boolean intact = rows.isEmpty()
                ? anchorHash == null
                : Objects.equals(anchorHash, prev);
        boolean anchorMatch = rows.isEmpty() ? anchorHash == null : Objects.equals(anchorHash, prev);
        String summary;
        if (rows.isEmpty()) {
            summary = anchorHash == null ? "链为空且无封印锚" : "链为空但存在封印锚（异常）";
        } else if (anchorMatch) {
            summary = "校验通过：共 " + rows.size() + " 条记录，链完整且与封印锚一致";
        } else {
            summary = "链内哈希一致，但封印锚与链末哈希不一致（锚可能被篡改或未同步）";
        }
        return new ChainVerifyResult(key, rows.size(), intact,
                null, null, null, anchorHash, anchorMatch, summary);
    }

    /* ---------- 封印链核心 ---------- */

    /**
     * 单链封印：事务内「读锚 → 逐条算哈希回写 → 链末锚 upsert」。
     * 返回本链封印条数；任何异常整链回滚。
     */
    private Integer sealChain(String workspaceKey, List<Map<String, Object>> rows) {
        Integer sealed = tx.execute(status -> {
            Map<String, Object> anchor = repository.getSeal(workspaceKey);
            String prev = anchor == null ? "" : str(anchor.get("lastHash"));
            for (Map<String, Object> row : rows) {
                String h = sha256Hex(prev + "\n" + canonical(row));
                repository.markSealed(str(row.get("id")), prev, h);
                prev = h;
            }
            String lastId = str(rows.get(rows.size() - 1).get("id"));
            repository.upsertSeal(workspaceKey, lastId, prev, rows.size());
            return rows.size();
        });
        return sealed == null ? 0 : sealed;
    }

    /**
     * 按 workspace 分链；用 LinkedHashMap 保持 id 升序（即时间序）进入每条链。
     */
    private Map<String, List<Map<String, Object>>> groupByChain(List<Map<String, Object>> rows) {
        return rows.stream().collect(Collectors.groupingBy(
                row -> chainKey(str(row.get("workspaceId"))),
                LinkedHashMap::new,
                Collectors.toList()));
    }

    /** 链名归一：空 workspace 归入 {@value #SYSTEM_CHAIN}。 */
    static String chainKey(String workspaceId) {
        return workspaceId == null || workspaceId.isBlank() ? SYSTEM_CHAIN : workspaceId;
    }

    /* ---------- 规范化与哈希 ---------- */

    /**
     * 记录规范化：固定字段顺序、{@code '|'} 连接、null→空串、时间戳统一 ISO-8601 UTC。
     * 任何字段的静默篡改都会改变规范化串，从而被重算哈希检出。
     */
    static String canonical(Map<String, Object> row) {
        return Stream.of(CANONICAL_FIELDS)
                .map(f -> normalize(row.get(f)))
                .collect(Collectors.joining("|"));
    }

    /** 单字段规范化：null→空串；时间戳→ISO-8601 UTC；其余按字符串原样。 */
    static String normalize(Object v) {
        if (v == null) {
            return "";
        }
        if (v instanceof OffsetDateTime odt) {
            return odt.toInstant().toString();
        }
        if (v instanceof Instant i) {
            return i.toString();
        }
        return String.valueOf(v);
    }

    /** sha256 十六进制摘要（小写）。 */
    static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /* ---------- 基础工具 ---------- */

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static String orEmpty(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    private static ChainVerifyResult broken(String key, int checked, String id,
                                            String expected, String actual, String why) {
        return new ChainVerifyResult(key, checked, false, id, expected, actual,
                null, false, why);
    }

    private void releaseLock() {
        try {
            if (lockHolder.equals(redis.opsForValue().get(LOCK_KEY))) {
                redis.delete(LOCK_KEY);
            }
        } catch (RuntimeException e) {
            log.warn("audit seal lock release failed (will expire by TTL): {}", e.getMessage());
        }
    }
}
