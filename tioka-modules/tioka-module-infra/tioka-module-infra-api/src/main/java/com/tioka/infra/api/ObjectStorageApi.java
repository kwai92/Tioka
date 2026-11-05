package com.tioka.infra.api;

/**
 * 对象交换层（ADR-014）；实现可在 runtime/infra。
 */
public interface ObjectStorageApi {
    String put(String key, byte[] content, String contentType);

    byte[] get(String key);

    DownloadTicket issueDownloadTicket(String objectKey);

    byte[] redeemTicket(String ticket);

    record DownloadTicket(String objectKey, String ticket, long expiresAtEpochMs) {}
}
