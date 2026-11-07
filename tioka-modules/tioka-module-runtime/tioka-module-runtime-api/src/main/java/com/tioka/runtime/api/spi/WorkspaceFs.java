package com.tioka.runtime.api.spi;

import java.nio.file.Path;

/**
 * Session POSIX 工作盘（ADR-014 D1）。逻辑路径：uploads/ skills/ scratch/ artifacts/out/
 */
public interface WorkspaceFs {
    Path root(String workspaceId, String threadId);

    Path resolve(String workspaceId, String threadId, String logicalPath);

    void ensureLayout(String workspaceId, String threadId);

    void writeBytes(String workspaceId, String threadId, String logicalPath, byte[] content);

    byte[] readBytes(String workspaceId, String threadId, String logicalPath);

    /**
     * 从对象存储 key 物化到工作盘逻辑路径（Wave 4 完整实现；Wave 1 可 Unsupported）。
     */
    default void materializeFromObject(String workspaceId, String threadId, String objectKey, String logicalDest) {
        throw new UnsupportedOperationException("materializeFromObject 未实现");
    }

    /**
     * 将 artifacts/out 下文件发布到对象存储。
     */
    default PublishedArtifact publishArtifact(String workspaceId, String threadId, String logicalSrc, String contentType) {
        throw new UnsupportedOperationException("publishArtifact 未实现");
    }

    record PublishedArtifact(String objectKey, String downloadTicket, long expiresAtEpochMs) {}
}
