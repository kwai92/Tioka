package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** AST-01：旧 Agent 配置组装为 AgentSpec（见 {@link AgentSpecBackfill}）。 */
public class V124__BackfillAgentSpec extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws Exception {
        AgentSpecBackfill.migrate(context.getConnection(), AgentSpecBackfill.Tables.defaults());
    }
}
