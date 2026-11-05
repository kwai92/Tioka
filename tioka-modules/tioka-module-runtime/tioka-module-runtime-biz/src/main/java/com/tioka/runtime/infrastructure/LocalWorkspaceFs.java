package com.tioka.runtime.infrastructure;

import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.runtime.api.spi.WorkspaceFs;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;

@Component
public class LocalWorkspaceFs implements WorkspaceFs {
    private final Path workRoot;
    private final LocalObjectStore objectStore;

    public LocalWorkspaceFs(@Value("${tioka.workspace.root:./data/workspaces}") String root,
                            LocalObjectStore objectStore) {
        this.workRoot = Path.of(root).toAbsolutePath().normalize();
        this.objectStore = objectStore;
    }

    /** 单测工厂。 */
    public static LocalWorkspaceFs forTest(Path root, LocalObjectStore objectStore) {
        return new LocalWorkspaceFs(root.toAbsolutePath().normalize().toString(), objectStore);
    }

    @Override
    public Path root(String workspaceId, String sessionId) {
        return workRoot.resolve(safeSeg(workspaceId)).resolve(safeSeg(sessionId)).normalize();
    }

    @Override
    public Path resolve(String workspaceId, String sessionId, String logicalPath) {
        Path base = root(workspaceId, sessionId);
        if (logicalPath == null || logicalPath.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "logicalPath 为空");
        }
        String normalized = logicalPath.replace('\\', '/').replaceAll("^/+", "");
        Path resolved = base.resolve(normalized).normalize();
        if (!resolved.startsWith(base)) {
            throw new BizException(ErrorCode.FORBIDDEN, "路径逃逸: " + logicalPath);
        }
        return resolved;
    }

    @Override
    public void ensureLayout(String workspaceId, String sessionId) {
        try {
            Path base = root(workspaceId, sessionId);
            Files.createDirectories(base.resolve("uploads"));
            Files.createDirectories(base.resolve("skills"));
            Files.createDirectories(base.resolve("scratch"));
            Files.createDirectories(base.resolve("artifacts").resolve("out"));
        } catch (IOException e) {
            throw new BizException(ErrorCode.INTERNAL, "创建工作盘失败: " + e.getMessage());
        }
    }

    @Override
    public void writeBytes(String workspaceId, String sessionId, String logicalPath, byte[] content) {
        try {
            Path target = resolve(workspaceId, sessionId, logicalPath);
            Files.createDirectories(target.getParent());
            Files.write(target, content == null ? new byte[0] : content);
        } catch (BizException e) {
            throw e;
        } catch (IOException e) {
            throw new BizException(ErrorCode.INTERNAL, "写入工作盘失败: " + e.getMessage());
        }
    }

    @Override
    public byte[] readBytes(String workspaceId, String sessionId, String logicalPath) {
        try {
            Path target = resolve(workspaceId, sessionId, logicalPath);
            if (!Files.isRegularFile(target)) {
                throw new BizException(ErrorCode.NOT_FOUND, "文件不存在: " + logicalPath);
            }
            return Files.readAllBytes(target);
        } catch (BizException e) {
            throw e;
        } catch (IOException e) {
            throw new BizException(ErrorCode.INTERNAL, "读取工作盘失败: " + e.getMessage());
        }
    }

    @Override
    public void materializeFromObject(String workspaceId, String sessionId, String objectKey, String logicalDest) {
        if (objectStore == null) {
            throw new UnsupportedOperationException("objectStore 未配置");
        }
        byte[] bytes = objectStore.get(objectKey);
        writeBytes(workspaceId, sessionId, logicalDest, bytes);
    }

    @Override
    public PublishedArtifact publishArtifact(String workspaceId, String sessionId, String logicalSrc, String contentType) {
        if (objectStore == null) {
            throw new UnsupportedOperationException("objectStore 未配置");
        }
        if (logicalSrc == null || !logicalSrc.replace('\\', '/').startsWith("artifacts/out/")) {
            throw new BizException(ErrorCode.BAD_REQUEST, "仅允许发布 artifacts/out/ 下文件");
        }
        byte[] bytes = readBytes(workspaceId, sessionId, logicalSrc);
        String name = Path.of(logicalSrc).getFileName().toString();
        String key = objectStore.put("artifacts/" + sessionId + "/" + name, bytes,
                contentType == null ? "application/octet-stream" : contentType);
        return objectStore.issueDownloadTicket(key);
    }

    private static String safeSeg(String seg) {
        String s = Objects.requireNonNullElse(seg, "default");
        if (s.contains("..") || s.contains("/") || s.contains("\\") || s.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "非法路径段: " + seg);
        }
        return s;
    }
}
