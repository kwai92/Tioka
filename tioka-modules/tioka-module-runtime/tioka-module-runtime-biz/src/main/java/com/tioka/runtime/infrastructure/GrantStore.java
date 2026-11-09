package com.tioka.runtime.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.runtime.infrastructure.mapper.RuntimeGrantMapper;
import com.tioka.runtime.infrastructure.persistence.RuntimeGrantDO;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;

/**
 * rt_grant 仓储（三层封装：application 不直持 Mapper）。
 */
@Repository
public class GrantStore {
    private final RuntimeGrantMapper mapper;
    private final ObjectMapper mapperJson;

    public GrantStore(RuntimeGrantMapper mapper, ObjectMapper mapperJson) {
        this.mapper = mapper;
        this.mapperJson = mapperJson;
    }

    public void insert(GrantBody body, String parentId, String rootPrincipal, String onBehalfOf,
                       String chainJson, int depth, java.time.Instant expiresAt) {
        RuntimeGrantDO row = new RuntimeGrantDO();
        row.setId(body.grant().grantId());
        row.setParentId(parentId);
        row.setRootPrincipal(rootPrincipal);
        row.setOnBehalfOf(onBehalfOf);
        row.setChainJson(chainJson);
        row.setDepth(depth);
        String json = writeJson(body);
        row.setBodyJson(json);
        row.setHash(sha256(json));
        row.setStatus(body.grant().status().name());
        row.setExpiresAt(LocalDateTime.ofInstant(expiresAt, ZoneOffset.UTC));
        mapper.insert(row);
    }

    public GrantBody findBody(String grantId) {
        RuntimeGrantDO row = mapper.findById(grantId);
        if (row == null) {
            return null;
        }
        try {
            return mapperJson.readValue(row.getBodyJson(), GrantBody.class);
        } catch (Exception e) {
            throw new BizException(ErrorCode.INTERNAL, "授予 body 解析失败: " + grantId);
        }
    }

    public RuntimeGrantDO findRow(String grantId) {
        return mapper.findById(grantId);
    }

    /** body CAS（乐观锁）；返回 false = 并发冲突需重试。 */
    public boolean casBody(String grantId, GrantBody oldBody, GrantBody newBody) {
        String oldJson = writeJson(oldBody);
        String newJson = writeJson(newBody);
        return mapper.updateBodyCas(grantId, oldJson, newJson, sha256(newJson)) > 0;
    }

    /** 级联置终态；返回受影响授予 ID。 */
    public List<String> reap(String grantId, String status) {
        return mapper.reapRecursive(grantId, status);
    }

    public long sumThreadTokens(String threadId) {
        return mapper.sumThreadTokens(threadId);
    }

    public int countActiveChildTurns(String parentTurnId) {
        return mapper.countActiveChildTurns(parentTurnId);
    }

    public List<String> findChildThreadIds(String parentTurnId) {
        return mapper.findChildThreadIds(parentTurnId);
    }

    private String writeJson(Object o) {
        try {
            return mapperJson.writeValueAsString(o);
        } catch (Exception e) {
            throw new BizException(ErrorCode.INTERNAL, "授予 JSON 序列化失败");
        }
    }

    static String sha256(String content) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
