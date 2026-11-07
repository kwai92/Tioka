package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * CHT-03：校验每个会话 {@code runtime_item} ≥ {@code chat_message} 后 DROP {@code chat_message}。
 * <p>V115 ADD 索引列已由 CHT-02 合入；本号占用 M1-00 预留的 V126 修正段，避免改已合并迁移。
 */
public class V126__DropChatMessage extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws Exception {
        ChatMessageDropGuard.validateAndDrop(context.getConnection(), ChatMessageDropGuard.Tables.defaults());
    }
}
