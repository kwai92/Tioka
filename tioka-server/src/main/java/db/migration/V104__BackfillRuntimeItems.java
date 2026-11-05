package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * ENG-11：占位，保证版本链含 V104。
 * RT-02 实现：把 runtime_session.parts_json 回填为 runtime_item / runtime_turn。
 */
public class V104__BackfillRuntimeItems extends BaseJavaMigration {
    @Override
    public void migrate(Context context) {
        // no-op until RT-02
    }
}
