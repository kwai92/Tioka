package com.tioka.testkit;

import com.tioka.framework.id.Ids;
import com.tioka.runtime.api.approval.ToolRisk;
import com.tioka.runtime.api.spi.ChangeOp;
import com.tioka.runtime.api.spi.ChangeSet;
import com.tioka.runtime.api.spi.Reversibility;
import com.tioka.runtime.api.spi.StaleChangeSetException;
import com.tioka.runtime.api.spi.ToolInvocation;
import com.tioka.runtime.api.spi.ToolProvider;
import com.tioka.runtime.api.spi.TransactionalTool;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存订单 / 应收单，实现 {@link TransactionalTool}（M2-05 / PK-24c）。
 * <p>可注入：{@link #mutateBefore}（漂移）、{@link #setCompensateFail}（补偿失败）。
 */
public final class FakeErp implements TransactionalTool {
    public static final String TOOL_UPDATE_ORDER = "fakeerp.order.update";
    public static final String TOOL_WRITEOFF_AR = "fakeerp.ar.writeoff";

    private final ConcurrentHashMap<String, Map<String, Object>> orders = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Map<String, Object>> ars = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ChangeSet.ApplyReceipt> applied = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> compensated = new ConcurrentHashMap<>();
    private volatile boolean compensateFail;

    public FakeErp seedOrder(String id, String status, long amountMicros) {
        orders.put(id, base(id, "order", status, amountMicros));
        return this;
    }

    public FakeErp seedAr(String id, String status, long amountMicros) {
        ars.put(id, base(id, "ar", status, amountMicros));
        return this;
    }

    /** 审批挂起期间改写 before，制造指纹漂移。 */
    public void mutateBefore(String objectType, String objectId, String field, Object value) {
        Map<String, Object> row = storeOf(objectType).get(objectId);
        if (row == null) {
            throw new IllegalArgumentException("unknown " + objectType + "/" + objectId);
        }
        Map<String, Object> copy = new LinkedHashMap<>(row);
        copy.put(field, value);
        storeOf(objectType).put(objectId, Map.copyOf(copy));
    }

    public void setCompensateFail(boolean fail) {
        this.compensateFail = fail;
    }

    public Map<String, Object> order(String id) {
        return orders.get(id);
    }

    public Map<String, Object> ar(String id) {
        return ars.get(id);
    }

    @Override
    public String name() {
        return "fakeerp";
    }

    @Override
    public List<ToolDescriptor> listTools(String agentVersionId) {
        return List.of(
                ToolDescriptor.builder()
                        .name(TOOL_UPDATE_ORDER)
                        .description("Update FakeErp order status")
                        .inputSchema(Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "orderId", Map.of("type", "string"),
                                        "status", Map.of("type", "string")),
                                "required", List.of("orderId", "status")))
                        .risk(ToolRisk.WRITE)
                        .reversibility(Reversibility.REVERSIBLE)
                        .build(),
                ToolDescriptor.builder()
                        .name(TOOL_WRITEOFF_AR)
                        .description("Write off FakeErp AR")
                        .inputSchema(Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "arId", Map.of("type", "string")),
                                "required", List.of("arId")))
                        .risk(ToolRisk.WRITE)
                        .reversibility(Reversibility.COMPENSABLE)
                        .build());
    }

    @Override
    public ToolResult invoke(String toolName, Map<String, Object> arguments) {
        // 写工具须走 preview/apply；直调仅用于探测
        return new ToolResult(false, "use TransactionalTool.preview/apply");
    }

    @Override
    public ChangeSet preview(ToolInvocation inv) {
        String tool = inv.toolName();
        Map<String, Object> args = inv.arguments();
        if (TOOL_UPDATE_ORDER.equals(tool)) {
            String id = str(args.get("orderId"));
            String status = str(args.get("status"));
            Map<String, Object> before = require(orders, id, "order");
            Map<String, Object> after = new LinkedHashMap<>(before);
            after.put("status", status);
            return changeSet("更新订单 " + id + " 状态 → " + status,
                    List.of(new ChangeSet.Change("order", id, ChangeOp.UPDATE, before, after)));
        }
        if (TOOL_WRITEOFF_AR.equals(tool)) {
            String id = str(args.get("arId"));
            Map<String, Object> before = require(ars, id, "ar");
            Map<String, Object> after = new LinkedHashMap<>(before);
            after.put("status", "WRITTEN_OFF");
            return changeSet("核销应收 " + id,
                    List.of(new ChangeSet.Change("ar", id, ChangeOp.UPDATE, before, after)));
        }
        throw new IllegalArgumentException("unknown tool: " + tool);
    }

    @Override
    public ChangeSet.ApplyReceipt apply(ToolInvocation inv, ChangeSet approved, String idempotencyKey) {
        if (idempotencyKey != null) {
            ChangeSet.ApplyReceipt cached = applied.get(idempotencyKey);
            if (cached != null) {
                return cached;
            }
        }
        String liveFp = fingerprintOf(approved.changes().stream()
                .map(c -> require(storeOf(c.objectType()), c.objectId(), c.objectType()))
                .toList());
        if (!liveFp.equals(approved.fingerprint())) {
            throw new StaleChangeSetException("数据已变化，请重新确认后再执行");
        }
        for (ChangeSet.Change c : approved.changes()) {
            storeOf(c.objectType()).put(c.objectId(), Map.copyOf(c.after()));
        }
        Map<String, Object> token = new LinkedHashMap<>();
        token.put("changes", approved.changes());
        ChangeSet.ApplyReceipt receipt = new ChangeSet.ApplyReceipt(
                Ids.newId("fer"), token, Instant.now());
        if (idempotencyKey != null) {
            applied.put(idempotencyKey, receipt);
        }
        return receipt;
    }

    @Override
    public ChangeSet.CompensationResult compensate(ChangeSet.ApplyReceipt receipt, String idempotencyKey) {
        if (idempotencyKey != null && Boolean.TRUE.equals(compensated.get(idempotencyKey))) {
            return new ChangeSet.CompensationResult(true, "already compensated", receipt.externalRef());
        }
        if (compensateFail) {
            return new ChangeSet.CompensationResult(false, "injected compensate failure",
                    receipt.externalRef());
        }
        Object raw = receipt.compensationToken().get("changes");
        if (!(raw instanceof List<?> list)) {
            return new ChangeSet.CompensationResult(false, "missing compensation token",
                    receipt.externalRef());
        }
        for (Object item : list) {
            // 回执经 trust_action.receipt_json 序列化往返后，changes 元素退化为 Map
            // （compensationToken 声明为 Map<String,Object>），离线直调时则是 ChangeSet.Change。
            // 两种形态都要能复原，否则真实 DB 链路上的撤销不会回滚业务数据。
            String objectType = null;
            String objectId = null;
            Map<String, Object> before = null;
            if (item instanceof ChangeSet.Change c) {
                objectType = c.objectType();
                objectId = c.objectId();
                before = c.before();
            } else if (item instanceof Map<?, ?> m) {
                objectType = str(m.get("objectType"));
                objectId = str(m.get("objectId"));
                if (m.get("before") instanceof Map<?, ?> bm) {
                    before = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> e : bm.entrySet()) {
                        before.put(String.valueOf(e.getKey()), e.getValue());
                    }
                }
            }
            if (objectType == null || objectType.isBlank() || objectId == null
                    || objectId.isBlank() || before == null) {
                continue;
            }
            storeOf(objectType).put(objectId, Map.copyOf(normalizeAmounts(before)));
        }
        if (idempotencyKey != null) {
            compensated.put(idempotencyKey, true);
        }
        return new ChangeSet.CompensationResult(true, "ok", receipt.externalRef());
    }

    /** JSON 往返会把 amountMicros 收窄成 Integer；复原时统一回 Long，与 seed 快照同型。 */
    private static Map<String, Object> normalizeAmounts(Map<String, Object> before) {
        Object amount = before.get("amountMicros");
        if (!(amount instanceof Number n) || amount instanceof Long) {
            return before;
        }
        Map<String, Object> out = new LinkedHashMap<>(before);
        out.put("amountMicros", n.longValue());
        return out;
    }

    private ChangeSet changeSet(String summary, List<ChangeSet.Change> changes) {
        List<Map<String, Object>> befores = changes.stream().map(ChangeSet.Change::before).toList();
        long amount = changes.stream()
                .mapToLong(c -> longOf(c.after().get("amountMicros")))
                .sum();
        Set<String> types = changes.stream().map(ChangeSet.Change::objectType).collect(java.util.stream.Collectors.toSet());
        return new ChangeSet(
                summary,
                changes,
                new ChangeSet.Totals(changes.size(), amount, "CNY", types),
                fingerprintOf(befores),
                false,
                Instant.now().plus(Duration.ofMinutes(30)));
    }

    private static Map<String, Object> base(String id, String type, String status, long amountMicros) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("objectType", type);
        m.put("status", status);
        m.put("amountMicros", amountMicros);
        return Map.copyOf(m);
    }

    private ConcurrentHashMap<String, Map<String, Object>> storeOf(String objectType) {
        return switch (objectType) {
            case "order" -> orders;
            case "ar" -> ars;
            default -> throw new IllegalArgumentException("unknown objectType: " + objectType);
        };
    }

    private static Map<String, Object> require(ConcurrentHashMap<String, Map<String, Object>> store,
                                               String id, String type) {
        Map<String, Object> row = store.get(id);
        if (row == null) {
            throw new IllegalArgumentException("unknown " + type + "/" + id);
        }
        return row;
    }

    static String fingerprintOf(List<Map<String, Object>> befores) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (Map<String, Object> b : befores) {
                md.update(String.valueOf(b).getBytes(StandardCharsets.UTF_8));
                md.update((byte) 0);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static long longOf(Object o) {
        if (o instanceof Number n) {
            return n.longValue();
        }
        if (o == null) {
            return 0L;
        }
        try {
            return Long.parseLong(String.valueOf(o));
        } catch (Exception e) {
            return 0L;
        }
    }
}
