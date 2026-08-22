package org.bluesky.dataprep.common;

import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.springframework.stereotype.Component;

import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;

/** Ensures V11 can derive a non-null altitude layer from legacy rows on fresh migrations. */
@Component
public class LegacyPerformanceMigrationGuard implements Callback {

    @Override
    public boolean supports(Event event, Context context) {
        return event == Event.BEFORE_EACH_MIGRATE;
    }

    @Override
    public boolean canHandleInTransaction(Event event, Context context) {
        return true;
    }

    @Override
    public void handle(Event event, Context context) {
        try {
            if (!tableExists(context, "aircraft_type_performance")) return;
            try (Statement statement = context.getConnection().createStatement()) {
                statement.executeUpdate("UPDATE aircraft_type_performance SET maximum_altitude_ft = 0 "
                        + "WHERE maximum_altitude_ft IS NULL");
            }
        } catch (Exception ex) {
            throw new IllegalStateException("旧版机型性能高度修复失败", ex);
        }
    }

    private boolean tableExists(Context context, String expected) throws Exception {
        DatabaseMetaData metadata = context.getConnection().getMetaData();
        try (ResultSet tables = metadata.getTables(null, null, null, new String[]{"TABLE"})) {
            while (tables.next()) {
                if (expected.equalsIgnoreCase(tables.getString("TABLE_NAME"))) return true;
            }
        }
        return false;
    }

    @Override
    public String getCallbackName() {
        return "legacy-performance-migration-guard";
    }
}
