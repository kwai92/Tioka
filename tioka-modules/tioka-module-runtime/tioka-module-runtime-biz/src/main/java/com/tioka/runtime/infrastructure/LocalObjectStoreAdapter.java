package com.tioka.runtime.infrastructure;

import com.tioka.infra.api.ObjectStorageApi;
import com.tioka.runtime.api.spi.WorkspaceFs;
import org.springframework.stereotype.Component;

@Component
public class LocalObjectStoreAdapter implements ObjectStorageApi {
    private final LocalObjectStore store;

    public LocalObjectStoreAdapter(LocalObjectStore store) {
        this.store = store;
    }

    @Override
    public String put(String key, byte[] content, String contentType) {
        return store.put(key, content, contentType);
    }

    @Override
    public byte[] get(String key) {
        return store.get(key);
    }

    @Override
    public DownloadTicket issueDownloadTicket(String objectKey) {
        WorkspaceFs.PublishedArtifact a = store.issueDownloadTicket(objectKey);
        return new DownloadTicket(a.objectKey(), a.downloadTicket(), a.expiresAtEpochMs());
    }

    @Override
    public byte[] redeemTicket(String ticket) {
        return store.redeem(ticket);
    }
}
