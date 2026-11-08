package com.tioka.connector.support;

import com.tioka.connector.infrastructure.FeishuDocRepository;
import com.tioka.connector.infrastructure.persistence.FeishuDocMapDO;
import com.tioka.connector.infrastructure.persistence.FeishuDocPushDO;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 单测用内存文档映射/镜像（不碰 PG；FeishuDocSyncIT 的本地替代）。 */
public final class InMemoryFeishuDocRepository extends FeishuDocRepository {
    private final Map<String, FeishuDocMapDO> maps = new ConcurrentHashMap<>();
    private final Map<String, FeishuDocPushDO> pushes = new ConcurrentHashMap<>();

    public InMemoryFeishuDocRepository() {
        super(null, null);
    }

    @Override
    public void insertMap(FeishuDocMapDO row) {
        maps.put(row.getId(), row);
    }

    @Override
    public FeishuDocMapDO findMap(String id) {
        FeishuDocMapDO row = maps.get(id);
        if (row == null) {
            throw new IllegalStateException("mapping not found: " + id);
        }
        return row;
    }

    @Override
    public List<FeishuDocMapDO> listMaps() {
        return new ArrayList<>(maps.values());
    }

    @Override
    public String findPushContent(String remote) {
        FeishuDocPushDO row = pushes.get(remote);
        return row == null ? null : row.getContent();
    }

    @Override
    public void upsertPush(String remote, String content) {
        FeishuDocPushDO row = pushes.get(remote);
        if (row == null) {
            row = new FeishuDocPushDO();
            row.setRemote(remote);
            row.setContent(content);
            pushes.put(remote, row);
        } else {
            row.setContent(content);
        }
    }

    public void deletePush(String remote) {
        pushes.remove(remote);
    }
}
