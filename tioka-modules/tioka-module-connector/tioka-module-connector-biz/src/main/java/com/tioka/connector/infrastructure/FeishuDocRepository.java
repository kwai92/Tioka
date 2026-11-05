package com.tioka.connector.infrastructure;

import com.tioka.connector.infrastructure.mapper.FeishuDocMapMapper;
import com.tioka.connector.infrastructure.mapper.FeishuDocPushMapper;
import com.tioka.connector.infrastructure.persistence.FeishuDocMapDO;
import com.tioka.connector.infrastructure.persistence.FeishuDocPushDO;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class FeishuDocRepository {
    private final FeishuDocMapMapper maps;
    private final FeishuDocPushMapper pushes;
    public FeishuDocRepository(FeishuDocMapMapper maps, FeishuDocPushMapper pushes) {
        this.maps = maps; this.pushes = pushes;
    }
    public void insertMap(FeishuDocMapDO row) { maps.insert(row); }
    public FeishuDocMapDO findMap(String id) { return maps.selectById(id); }
    public List<FeishuDocMapDO> listMaps() { return maps.selectList(null); }
    public String findPushContent(String remote) {
        FeishuDocPushDO row = pushes.selectById(remote);
        return row == null ? null : row.getContent();
    }
    public void upsertPush(String remote, String content) {
        FeishuDocPushDO row = pushes.selectById(remote);
        if (row == null) {
            row = new FeishuDocPushDO();
            row.setRemote(remote);
            row.setContent(content);
            pushes.insert(row);
        } else {
            row.setContent(content);
            pushes.updateById(row);
        }
    }
}
