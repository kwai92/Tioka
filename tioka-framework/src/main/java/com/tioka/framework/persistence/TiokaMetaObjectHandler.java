package com.tioka.framework.persistence;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
public class TiokaMetaObjectHandler implements MetaObjectHandler {
    private final PrincipalHolder principalHolder;

    public TiokaMetaObjectHandler(PrincipalHolder principalHolder) {
        this.principalHolder = principalHolder;
    }

    @Override
    public void insertFill(MetaObject metaObject) {
        LocalDateTime now = LocalDateTime.now();
        String operator = OperatorAuditor.currentOperator();
        strictInsertFill(metaObject, "createAt", LocalDateTime.class, now);
        strictInsertFill(metaObject, "updateAt", LocalDateTime.class, now);
        strictInsertFill(metaObject, "createBy", String.class, operator);
        strictInsertFill(metaObject, "updateBy", String.class, operator);
        Object deleted = getFieldValByName("deleted", metaObject);
        if (deleted == null) {
            strictInsertFill(metaObject, "deleted", Boolean.class, Boolean.FALSE);
        }
        fillOwnership(metaObject);
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        strictUpdateFill(metaObject, "updateAt", LocalDateTime.class, LocalDateTime.now());
        strictUpdateFill(metaObject, "updateBy", String.class, OperatorAuditor.currentOperator());
    }

    private void fillOwnership(MetaObject metaObject) {
        Principal p = principalHolder.find().orElse(null);
        if (p == null) {
            return;
        }
        Object ws = getFieldValByName("workspaceId", metaObject);
        if ((ws == null || String.valueOf(ws).isBlank())
                && p.workspaceId() != null && !p.workspaceId().isBlank()) {
            strictInsertFill(metaObject, "workspaceId", String.class, p.workspaceId());
        }
        Object owner = getFieldValByName("ownerId", metaObject);
        if ((owner == null || String.valueOf(owner).isBlank())
                && p.userId() != null && !p.userId().isBlank()) {
            strictInsertFill(metaObject, "ownerId", String.class, p.userId());
        }
    }
}
