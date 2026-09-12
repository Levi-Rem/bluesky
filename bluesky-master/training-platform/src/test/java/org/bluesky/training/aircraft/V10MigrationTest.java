package org.bluesky.training.aircraft;

import org.bluesky.training.TrainingPlatformApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** P07：V10 迁移验证（TDD 计划第 4 节 givenLegacyAircraftWhenMigratedThenCurrentAssignmentIsUnique）。 */
@SpringBootTest(classes = TrainingPlatformApplication.class)
@ActiveProfiles("test")
class V10MigrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    private String newGroupWithTerminals(String label, String... terminalIds) {
        String groupId = "group-" + label + "-" + System.nanoTime();
        jdbc.update("INSERT INTO exercise_group (id, name, state) VALUES (?, ?, 'READY')",
                groupId, label + "组");
        for (String terminalId : terminalIds) {
            jdbc.update("INSERT INTO workstation_terminal (id, name, terminal_type, "
                            + "exercise_group_id) VALUES (?, '机长席', 'PSEUDO_PILOT', ?)",
                    terminalId + "-" + groupId.substring(groupId.length() - 8), groupId);
        }
        return groupId;
    }

    @Test
    void givenLegacyAircraftWhenMigratedThenCurrentAssignmentIsUnique() {
        Integer multiAssigned = jdbc.queryForObject(
                "SELECT COUNT(*) FROM (SELECT aircraft_id FROM aircraft_assignment "
                        + "WHERE ended_at IS NULL GROUP BY aircraft_id HAVING COUNT(*) > 1)",
                Integer.class);
        assertEquals(0, multiAssigned.intValue(), "每架航空器最多一条当前分配");

        // 共享 H2 中还混有 v1 API 测试新建的航空器（无计划属 v1 语义），
        // 因此断言回填产物本身：存在 v1 计划、版本唯一、且每条当前分配都有对应计划
        Integer flightPlanTable = jdbc.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = 'flight_plan'",
                Integer.class);
        assertEquals(1, flightPlanTable.intValue(), "迁移必须建立版本化计划表");
        // 只约束 V10 迁移回填的遗留分配（v1 迁移门面建机不写 v2 flight_plan，
        // 运行期由 v2 createPlan 全链负责；共享 H2 下其他测试的 v1 建机不属于迁移契约）
        Integer legacyOrphans = jdbc.queryForObject(
                "SELECT COUNT(*) FROM aircraft_assignment a "
                        + "JOIN exercise_aircraft c ON c.id = a.aircraft_id "
                        + "WHERE a.ended_at IS NULL AND a.id LIKE '%-a1' "
                        + "AND NOT EXISTS (SELECT 1 FROM flight_plan p "
                        + "WHERE p.aircraft_id = a.aircraft_id)",
                Integer.class);
        assertEquals(0, legacyOrphans.intValue(), "迁移回填的每条当前分配都必须有初始计划");
    }

    @Test
    void givenSecondCurrentAssignmentWhenInsertedThenDatabaseRejects() {
        String groupId = newGroupWithTerminals("uniq", "T1", "T2");
        String t1 = "T1-" + groupId.substring(groupId.length() - 8);
        String t2 = "T2-" + groupId.substring(groupId.length() - 8);
        String aircraftId = "ac-uniq-" + System.nanoTime();
        jdbc.update("INSERT INTO exercise_aircraft (id, exercise_group_id, assigned_terminal_id, "
                        + "callsign, aircraft_type, wake_category, origin, destination, "
                        + "heading_degrees, altitude_feet, speed_knots) "
                        + "VALUES (?, ?, ?, 'UNI1', 'A320', 'M', 'ZGGG', 'ZBAA', 20, 8000, 250)",
                aircraftId, groupId, t1);
        insertPlan(aircraftId);
        jdbc.update("INSERT INTO aircraft_assignment (id, aircraft_id, terminal_id, current_key) "
                + "VALUES (?, ?, ?, ?)", "as-1-" + aircraftId, aircraftId, t1, aircraftId);

        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "INSERT INTO aircraft_assignment (id, aircraft_id, terminal_id, current_key) "
                        + "VALUES ('as-2', ?, ?, ?)", aircraftId, t2, aircraftId),
                "同一航空器的第二条当前分配必须被唯一约束拒绝");
    }

    @Test
    void givenClosedAssignmentWhenReopenedAnotherThenAllowed() {
        String groupId = newGroupWithTerminals("reop", "T1", "T2");
        String t1 = "T1-" + groupId.substring(groupId.length() - 8);
        String t2 = "T2-" + groupId.substring(groupId.length() - 8);
        String aircraftId = "ac-reop-" + System.nanoTime();
        jdbc.update("INSERT INTO exercise_aircraft (id, exercise_group_id, assigned_terminal_id, "
                        + "callsign, aircraft_type, wake_category, origin, destination, "
                        + "heading_degrees, altitude_feet, speed_knots) "
                        + "VALUES (?, ?, ?, 'REO1', 'A320', 'M', 'ZGGG', 'ZBAA', 20, 8000, 250)",
                aircraftId, groupId, t1);
        insertPlan(aircraftId);
        jdbc.update("INSERT INTO aircraft_assignment (id, aircraft_id, terminal_id, current_key) "
                + "VALUES (?, ?, ?, ?)", "as-1-" + aircraftId, aircraftId, t1, aircraftId);

        jdbc.update("UPDATE aircraft_assignment SET ended_at = CURRENT_TIMESTAMP(3), "
                + "current_key = NULL WHERE aircraft_id = ?", aircraftId);
        jdbc.update("INSERT INTO aircraft_assignment (id, aircraft_id, terminal_id, current_key) "
                + "VALUES (?, ?, ?, ?)", "as-2-" + aircraftId, aircraftId, t2, aircraftId);

        Integer current = jdbc.queryForObject(
                "SELECT COUNT(*) FROM aircraft_assignment WHERE aircraft_id = ? "
                        + "AND ended_at IS NULL", Integer.class, aircraftId);
        assertEquals(1, current.intValue(), "结束旧分配后可以建立新的唯一当前分配");
    }

    private void insertPlan(String aircraftId) {
        jdbc.update("INSERT INTO flight_plan (id, aircraft_id, plan_version, origin, destination, "
                        + "route_text) VALUES (?, ?, 1, 'ZGGG', 'ZBAA', 'ZGGG ZBAA')",
                "plan-" + aircraftId, aircraftId);
    }

    @Test
    void givenInvalidLifecycleWhenInsertedThenDatabaseRejects() {
        String groupId = newGroupWithTerminals("chk", "T1");
        String t1 = "T1-" + groupId.substring(groupId.length() - 8);
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "INSERT INTO exercise_aircraft (id, exercise_group_id, assigned_terminal_id, "
                        + "callsign, aircraft_type, wake_category, origin, destination, "
                        + "heading_degrees, altitude_feet, speed_knots, lifecycle) "
                        + "VALUES ('ac-bogus', ?, ?, 'BOG', 'A320', 'M', 'ZGGG', 'ZBAA', "
                        + "20, 8000, 250, 'FLYING')", groupId, t1),
                "非法生命周期必须被 CHECK 拒绝");
    }

    @Test
    void givenDeletedAircraftWhenCallsignReusedThenAllowedButActiveDuplicateRejected() {
        String groupId = newGroupWithTerminals("callsign", "T1");
        String terminalId = "T1-" + groupId.substring(groupId.length() - 8);
        insertAircraft(groupId, terminalId, "ac-old-" + System.nanoTime(), "REUSE1", null);
        jdbc.update("UPDATE exercise_aircraft SET lifecycle = 'DELETED', deleted_at = "
                + "CURRENT_TIMESTAMP(3), active_callsign_key = NULL WHERE callsign = 'REUSE1' "
                + "AND exercise_group_id = ?", groupId);

        String activeId = "ac-new-" + System.nanoTime();
        insertAircraft(groupId, terminalId, activeId, "REUSE1", "REUSE1");
        assertThrows(DataIntegrityViolationException.class, () ->
                insertAircraft(groupId, terminalId, "ac-dup-" + System.nanoTime(),
                        "REUSE1", "REUSE1"));
    }

    private void insertAircraft(String groupId, String terminalId, String aircraftId,
                                String callsign, String activeCallsignKey) {
        jdbc.update("INSERT INTO exercise_aircraft (id, exercise_group_id, assigned_terminal_id, "
                        + "callsign, aircraft_type, wake_category, origin, destination, "
                        + "heading_degrees, altitude_feet, speed_knots, lifecycle, "
                        + "active_callsign_key) VALUES (?, ?, ?, ?, 'A320', 'M', 'ZGGG', 'ZBAA', "
                        + "20, 8000, 250, 'PLANNED', ?)",
                aircraftId, groupId, terminalId, callsign, activeCallsignKey);
    }
}
