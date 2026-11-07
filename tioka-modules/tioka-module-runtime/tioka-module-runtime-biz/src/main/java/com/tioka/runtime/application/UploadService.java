package com.tioka.runtime.application;

import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.id.Ids;
import com.tioka.infra.api.ObjectStorageApi;
import com.tioka.runtime.api.spi.WorkspaceFs;
import com.tioka.runtime.infrastructure.RuntimeThreadRepository;
import com.tioka.runtime.infrastructure.persistence.RuntimeThreadDO;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class UploadService {
    private final ObjectStorageApi objects;
    private final WorkspaceFs workspaceFs;
    private final RuntimeThreadRepository threads;
    private final Map<String, UploadMeta> pending = new ConcurrentHashMap<>();

    public UploadService(ObjectStorageApi objects, WorkspaceFs workspaceFs, RuntimeThreadRepository threads) {
        this.objects = objects;
        this.workspaceFs = workspaceFs;
        this.threads = threads;
    }

    public PresignResult presign(String filename, String contentType, String threadId) {
        String uploadId = Ids.newId("up");
        String objectKey = "uploads/" + uploadId + "/" + sanitize(filename);
        pending.put(uploadId, new UploadMeta(objectKey, threadId, filename, contentType, false));
        return new PresignResult(uploadId, objectKey, "/api/uploads/" + uploadId + "/content");
    }

    public void putContent(String uploadId, byte[] body) {
        UploadMeta meta = pending.get(uploadId);
        if (meta == null) throw new BizException(ErrorCode.NOT_FOUND, "upload 不存在");
        objects.put(meta.objectKey(), body, meta.contentType());
        pending.put(uploadId, new UploadMeta(meta.objectKey(), meta.threadId(), meta.filename(), meta.contentType(), true));
    }

    public ConfirmResult confirm(String uploadId, String threadId) {
        UploadMeta meta = pending.get(uploadId);
        if (meta == null) throw new BizException(ErrorCode.NOT_FOUND, "upload 不存在");
        if (!meta.written()) throw new BizException(ErrorCode.BAD_REQUEST, "尚未上传内容");
        if (threadId != null && meta.threadId() != null && !meta.threadId().equals(threadId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "附件不属于当前会话");
        }
        String sid = threadId != null ? threadId : meta.threadId();
        if (sid == null) throw new BizException(ErrorCode.BAD_REQUEST, "缺少 threadId");
        String workspaceId = workspaceId(sid);
        String logical = "uploads/" + uploadId + "/" + sanitize(meta.filename());
        workspaceFs.materializeFromObject(workspaceId, sid, meta.objectKey(), logical);
        return new ConfirmResult(uploadId, logical, Map.of(
                "type", "file_ref",
                "uploadId", uploadId,
                "logicalPath", logical,
                "mime", meta.contentType() == null ? "application/octet-stream" : meta.contentType()
        ));
    }

    public WorkspaceFs.PublishedArtifact publish(String threadId, String logicalSrc, String contentType) {
        String workspaceId = workspaceId(threadId);
        return workspaceFs.publishArtifact(workspaceId, threadId, logicalSrc, contentType);
    }

    public byte[] downloadByTicket(String ticket) {
        return objects.redeemTicket(ticket);
    }

    private String workspaceId(String threadId) {
        RuntimeThreadDO row = threads.find(threadId);
        if (row == null || row.getWorkspaceId() == null || row.getWorkspaceId().isBlank()) {
            throw new BizException(ErrorCode.NOT_FOUND, "会话不存在");
        }
        return row.getWorkspaceId();
    }

    private static String sanitize(String name) {
        if (name == null || name.isBlank()) return "file.bin";
        return name.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private record UploadMeta(String objectKey, String threadId, String filename, String contentType, boolean written) {}

    public record PresignResult(String uploadId, String objectKey, String putUrl) {}

    public record ConfirmResult(String uploadId, String logicalPath, Map<String, Object> fileRef) {}
}
