package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;

/**
 * v1 used permanent UNIQUE(group,callsign); v2 keeps historical callsigns and makes only
 * active_callsign_key unique. MySQL and H2 use different DDL to remove the legacy key.
 */
public class V10_1__replace_legacy_callsign_uniqueness extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        String database = connection.getMetaData().getDatabaseProductName()
                .toLowerCase(Locale.ROOT);
        if (database.contains("h2")) {
            execute(connection, "ALTER TABLE exercise_aircraft "
                    + "DROP CONSTRAINT uq_aircraft_group_callsign");
            return;
        }
        if (database.contains("mysql") || database.contains("mariadb")) {
            execute(connection, "ALTER TABLE exercise_aircraft "
                    + "DROP INDEX uq_aircraft_group_callsign");
            return;
        }
        throw new SQLException("Unsupported database for callsign uniqueness migration: "
                + connection.getMetaData().getDatabaseProductName());
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
