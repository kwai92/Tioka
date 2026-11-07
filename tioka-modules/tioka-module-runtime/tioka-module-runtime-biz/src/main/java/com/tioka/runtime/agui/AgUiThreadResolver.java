package com.tioka.runtime.agui;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.runtime.api.kernel.RuntimeApi;
import com.tioka.runtime.infrastructure.mapper.RuntimeThreadMapper;
import com.tioka.runtime.infrastructure.persistence.RuntimeThreadDO;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * AG-UI {@code threadId} ↔ 内部 {@code runtime_thread}（metadata_json.aguiThreadId）。
 */
@Component
public class AgUiThreadResolver {
    private final RuntimeThreadMapper threads;
    private final RuntimeApi runtime;
    private final PrincipalHolder principals;
    private final ObjectMapper mapper;

    public AgUiThreadResolver(RuntimeThreadMapper threads,
                              RuntimeApi runtime,
                              PrincipalHolder principals,
                              ObjectMapper mapper) {
        this.threads = threads;
        this.runtime = runtime;
        this.principals = principals;
        this.mapper = mapper;
    }

    /**
     * 按当前用户查找已绑定的 thread；不存在则 createThread(origin=chat, metadata.aguiThreadId)。
     *
     * @return 内部 threadId
     */
    public String resolveOrCreate(String aguiThreadId, String agentVersionId) {
        if (aguiThreadId == null || aguiThreadId.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "threadId 必填");
        }
        Principal p = principals.current();
        RuntimeThreadDO existing = findByAguiThreadId(p.userId(), p.workspaceId(), aguiThreadId);
        if (existing != null) {
            return existing.getId();
        }
        RuntimeApi.ThreadView created = runtime.createThread(new RuntimeApi.CreateThreadCmd(
                agentVersionId, "chat", null, null, Map.of("aguiThreadId", aguiThreadId)));
        return created.threadId();
    }

    private RuntimeThreadDO findByAguiThreadId(String ownerId, String workspaceId, String aguiThreadId) {
        List<RuntimeThreadDO> rows = threads.selectList(new LambdaQueryWrapper<RuntimeThreadDO>()
                .eq(RuntimeThreadDO::getOwnerId, ownerId)
                .eq(RuntimeThreadDO::getWorkspaceId, workspaceId)
                .isNotNull(RuntimeThreadDO::getMetadataJson)
                .like(RuntimeThreadDO::getMetadataJson, "\"aguiThreadId\"")
                .orderByDesc(RuntimeThreadDO::getId)
                .last("LIMIT 50"));
        for (RuntimeThreadDO row : rows) {
            if (matchesAguiThreadId(row.getMetadataJson(), aguiThreadId)) {
                return row;
            }
        }
        return null;
    }

    private boolean matchesAguiThreadId(String metadataJson, String aguiThreadId) {
        if (metadataJson == null || metadataJson.isBlank()) {
            return false;
        }
        try {
            JsonNode n = mapper.readTree(metadataJson);
            return aguiThreadId.equals(n.path("aguiThreadId").asText(null));
        } catch (Exception e) {
            return false;
        }
    }
}
