package com.tioka.system.infrastructure;

import com.tioka.system.infrastructure.mapper.ScimUserMapper;
import com.tioka.system.infrastructure.persistence.ScimUserDO;
import org.springframework.stereotype.Repository;

@Repository
public class ScimUserRepository {
    private final ScimUserMapper mapper;

    public ScimUserRepository(ScimUserMapper mapper) {
        this.mapper = mapper;
    }

    public void upsert(ScimUserDO row) {
        if (mapper.selectById(row.getId()) == null) {
            mapper.insert(row);
        } else {
            mapper.updateById(row);
        }
    }

    public boolean softDelete(String id) {
        return mapper.deleteById(id) > 0;
    }

    public long count() {
        return mapper.selectCount(null);
    }
}
