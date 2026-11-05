package com.tioka.runtime.infrastructure;

import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.runtime.api.spi.WorkspaceFs.PublishedArtifact;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 本地目录对象存储（ADR-014 交换层起步；可替换 MinIO）。
 */
@Component
public class LocalObjectStore {
    private final Path root;
    private final Map<String, Ticket> tickets = new ConcurrentHashMap<>();

    public LocalObjectStore(@Value("${tioka.object.root:./data/objects}") String root) {
        this.root = Path.of(root).toAbsolutePath().normalize();
    }

    public String put(String key, byte[] content, String contentType) {
        try {
            Path target = resolveKey(key);
            Files.createDirectories(target.getParent());
            Files.write(target, content == null ? new byte[0] : content);
            Files.writeString(Path.of(target + ".ctype"), contentType == null ? "application/octet-stream" : contentType);
            return key;
        } catch (IOException e) {
            throw new BizException(ErrorCode.INTERNAL, "对象写入失败: " + e.getMessage());
        }
    }

    public byte[] get(String key) {
        try {
            Path target = resolveKey(key);
            if (!Files.isRegularFile(target)) {
                throw new BizException(ErrorCode.NOT_FOUND, "对象不存在: " + key);
            }
            return Files.readAllBytes(target);
        } catch (BizException e) {
            throw e;
        } catch (IOException e) {
            throw new BizException(ErrorCode.INTERNAL, "对象读取失败: " + e.getMessage());
        }
    }

    public boolean exists(String key) {
        return Files.isRegularFile(resolveKey(key));
    }

    public PublishedArtifact issueDownloadTicket(String objectKey) {
        String ticket = "tkt_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        long exp = System.currentTimeMillis() + 15 * 60_000L;
        tickets.put(ticket, new Ticket(objectKey, exp));
        return new PublishedArtifact(objectKey, ticket, exp);
    }

    public byte[] redeem(String ticket) {
        Ticket t = tickets.get(ticket);
        if (t == null || t.expiresAtEpochMs() < System.currentTimeMillis()) {
            throw new BizException(ErrorCode.FORBIDDEN, "下载票证无效或过期");
        }
        return get(t.objectKey());
    }

    private Path resolveKey(String key) {
        if (key == null || key.isBlank() || key.contains("..")) {
            throw new BizException(ErrorCode.BAD_REQUEST, "非法 objectKey");
        }
        Path p = root.resolve(key).normalize();
        if (!p.startsWith(root)) {
            throw new BizException(ErrorCode.FORBIDDEN, "objectKey 逃逸");
        }
        return p;
    }

    private record Ticket(String objectKey, long expiresAtEpochMs) {}
}
