package org.bluesky.training.common;

import org.bluesky.training.TrainingPlatformApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** P20：V15 终迁移验证（TDD 计划第 4 节：非法枚举/重复当前键被数据库拒绝）。 */
@SpringBootTest(classes = TrainingPlatformApplication.class)
@ActiveProfiles("test")
class V15MigrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void givenInvalidEnumOrDuplicateCurrentKeyWhenInsertedThenDatabaseRejectsIt() {
        String groupId = "g15-" + System.nanoTime();

        // 非法训练组状态
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "INSERT INTO exercise_group (id, name, state) VALUES (?, 'x', 'BOOTING')",
                groupId));

        // 非法指令状态（v1 的 PENDING 已被 V15 CHECK 拒绝）
        String okGroup = "g15-ok-" + System.nanoTime();
        jdbc.update("INSERT INTO exercise_group (id, name, state) VALUES (?, 'x', 'READY')",
                okGroup);
        String terminalId = "T15-" + System.nanoTime();
        jdbc.update("INSERT INTO workstation_terminal (id, name, terminal_type, "
                + "exercise_group_id) VALUES (?, 'x', 'PSEUDO_PILOT', ?)", terminalId, okGroup);
        String aircraftId = "a15-" + System.nanoTime();
        String callsign = "C15" + System.nanoTime() % 100000;
        jdbc.update("INSERT INTO exercise_aircraft (id, exercise_group_id, assigned_terminal_id, "
                        + "callsign, aircraft_type, wake_category, origin, destination, "
                        + "heading_degrees, altitude_feet, speed_knots, active_callsign_key) "
                        + "VALUES (?, ?, ?, ?, 'A320', 'M', 'ZGGG', 'ZBAA', 20, 8000, 250, ?)",
                aircraftId, okGroup, terminalId, callsign, callsign);
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "INSERT INTO aircraft_instruction (id, exercise_aircraft_id, raw_text, "
                        + "instruction_type, insertion_mode, status, sequence_number, "
                        + "conflict_key) VALUES ('ins-bogus', ?, 'X', 'HDG', 'REPLACE', "
                        + "'PENDING', 1, 'LATERAL:" + aircraftId + "')", aircraftId),
                "V15 后 PENDING 必须被拒绝（旧语义退役）");

        // 活动呼号唯一收紧：同组同活动呼号第二条被唯一索引拒绝
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "INSERT INTO exercise_aircraft (id, exercise_group_id, assigned_terminal_id, "
                        + "callsign, aircraft_type, wake_category, origin, destination, "
                        + "heading_degrees, altitude_feet, speed_knots, active_callsign_key) "
                        + "VALUES ('a15-dup', ?, ?, ?, 'A320', 'M', 'ZGGG', 'ZBAA', 20, 8000, "
                        + "250, ?)", okGroup, terminalId, callsign, callsign),
                "同组活动呼号键唯一");

        // 正常 v2 状态仍可写入
        jdbc.update("INSERT INTO aircraft_instruction (id, exercise_aircraft_id, raw_text, "
                + "instruction_type, insertion_mode, status, sequence_number, conflict_key) "
                + "VALUES ('ins-ok15', ?, 'HDG 090', 'HDG', 'REPLACE', 'RECEIVED', 1, "
                + "'LATERALT:" + aircraftId + "')", aircraftId);
        assertEquals("RECEIVED", jdbc.queryForObject(
                "SELECT status FROM aircraft_instruction WHERE id = 'ins-ok15'",
                String.class));
        assertTrue(okGroup.length() > 0);
    }
}
