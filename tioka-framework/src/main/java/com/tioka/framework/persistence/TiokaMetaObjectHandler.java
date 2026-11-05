package com.tioka.framework.persistence;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
public class TiokaMetaObjectHandler implements MetaObjectHandler {

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
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        strictUpdateFill(metaObject, "updateAt", LocalDateTime.class, LocalDateTime.now());
        strictUpdateFill(metaObject, "updateBy", String.class, OperatorAuditor.currentOperator());
    }
}
