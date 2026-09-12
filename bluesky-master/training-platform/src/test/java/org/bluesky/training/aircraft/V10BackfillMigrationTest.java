package org.bluesky.training.aircraft;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0-1 回归：带 v1 遗留航空器的库执行 V10 迁移必须成功（评审：旧实现
 * CONCAT(id,'-p1') 以 39 字符溢出 CHAR(36)，MySQL 严格模式 1406）。
 * 既有 V10MigrationTest 在迁移后才插入数据，回填 SELECT 始终作用于空表，
 * 掩盖了该缺陷；本测试用独立数据库先注入 V9 前置遗留行再跑 Flyway。
 */
class V10BackfillMigrationTest {

    private JdbcTemplate newDatabaseAtV9() {
        String url = "jdbc:h2:mem:v10backfill-" + System.nanoTime()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE";
        migrate(url, "9");
        SimpleDriverDataSource dataSource = new SimpleDriverDataSource();
        dataSource.setDriverClass(org.h2.Driver.class);
        dataSource.setUrl(url);
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        return new JdbcTemplate(dataSource);
    }

    private static void migrate(String url, String target) {
        Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/migration")
                .target(target)
                .load()
                .migrate();
    }

    private static void migrateAll(String url) {
        Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    /** 在 V9 结构（V10 加列之前）注入一架 v1 遗留航空器，id 为 36 字符 UUID。 */
    private void insertLegacyAircraft(JdbcTemplate jdbc) {
        String groupId = "grp-" + UUID.randomUUID();
        String terminalId = "trm-" + UUID.randomUUID();
        String aircraftId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO exercise_group (id, name, state) VALUES (?, ?, 'READY')",
                groupId, "回填验证组");
        jdbc.update("INSERT INTO workstation_terminal (id, name, terminal_type, exercise_group_id) "
                + "VALUES (?, '机长席', 'PSEUDO_PILOT', ?)", terminalId, groupId);
        jdbc.update("INSERT INTO exercise_aircraft (id, exercise_group_id, assigned_terminal_id, "
                        + "callsign, aircraft_type, wake_category, transponder_code, origin, "
                        + "destination, heading_degrees, altitude_feet, speed_knots, route_text) "
                        + "VALUES (?, ?, ?, 'BKF1', 'A320', 'M', '3421', 'ZGGG', 'ZBAA', 20, 8000, "
                        + "250, 'ZGGG ZBAA')",
                aircraftId, groupId, terminalId);
    }

    @Test
    void givenLegacyAircraftBeforeV10WhenMigratedThenBackfillKeysFitChar36() {
        JdbcTemplate jdbc = newDatabaseAtV9();
        insertLegacyAircraft(jdbc);
        migrateAll(databaseUrlOf(jdbc));
        assertLegacyBackfilled(jdbc);
    }

    @Test
    void givenMigratedDatabaseWhenFlywayRunsAgainThenBackfillNotDuplicated() {
        JdbcTemplate jdbc = newDatabaseAtV9();
        insertLegacyAircraft(jdbc);
        migrateAll(databaseUrlOf(jdbc));
        migrateAll(databaseUrlOf(jdbc));

        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM flight_plan", Integer.class).intValue(),
                "重复触发迁移不得产生重复回填计划");
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM aircraft_assignment WHERE current_key IS NOT NULL",
                Integer.class).intValue(), "重复触发迁移不得产生重复当前分配");
    }

    private static String databaseUrlOf(JdbcTemplate jdbc) {
        SimpleDriverDataSource dataSource = (SimpleDriverDataSource) jdbc.getDataSource();
        return dataSource.getUrl();
    }

    private static void assertLegacyBackfilled(JdbcTemplate jdbc) {
        assertEquals("ACTIVE", jdbc.queryForObject(
                "SELECT lifecycle FROM exercise_aircraft", String.class),
                "遗留航空器迁移后为 ACTIVE");
        assertEquals(jdbc.queryForObject(
                "SELECT callsign FROM exercise_aircraft", String.class),
                jdbc.queryForObject("SELECT active_callsign_key FROM exercise_aircraft", String.class),
                "活动呼号键回填为原呼号");
        assertTrue(jdbc.queryForObject(
                "SELECT MAX(CHAR_LENGTH(id)) FROM flight_plan", Integer.class) <= 36,
                "flight_plan 主键必须在 CHAR(36) 内（P0-1）");
        assertTrue(jdbc.queryForObject(
                "SELECT MAX(CHAR_LENGTH(id)) FROM aircraft_assignment", Integer.class) <= 36,
                "aircraft_assignment 主键必须在 CHAR(36) 内（P0-1）");
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM flight_plan WHERE plan_version = 1", Integer.class).intValue(),
                "每架遗留航空器回填一条 v1 计划");
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM aircraft_assignment WHERE current_key IS NOT NULL",
                Integer.class).intValue(), "每架遗留航空器回填一条唯一当前分配");
    }
}
