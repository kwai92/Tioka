package com.tioka.infra.api;

/** @deprecated 使用 {@link ObjectStorageApi} */
@Deprecated
public interface FileStorageApi {
    String putObject(String bucket, String key, byte[] content);
}
