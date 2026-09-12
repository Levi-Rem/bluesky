package org.bluesky.training.exercise;

import org.bluesky.training.TrainingPlatformApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** P05：一组失败不影响其他组（详细设计 14.4：单组引擎故障不影响其他组）。 */
@SpringBootTest(classes = TrainingPlatformApplication.class)
@ActiveProfiles("test")
class MultiGroupIsolationTest {

    @Autowired
    private ExerciseGroupService exerciseGroupService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private String newGroup(String state) {
        String groupId = "group-iso-" + UUID.randomUUID();
        jdbc.update("INSERT INTO exercise_group (id, name, state) VALUES (?, ?, ?)",
                groupId, "隔离组", state);
        return groupId;
    }

    @Test
    void givenOneEngineFailsThenOtherGroupKeepsRunning() {
        String failing = newGroup("STARTING");
        String healthy = newGroup("RUNNING");
        long healthyRevision = jdbc.queryForObject(
                "SELECT revision FROM exercise_group WHERE id = ?", Long.class, healthy);

        transactionTemplate.execute(status ->
                exerciseGroupService.compensateFailedStart(failing, "引擎启动失败"));

        assertEquals("READY", jdbc.queryForObject(
                "SELECT state FROM exercise_group WHERE id = ?", String.class, failing));
        assertEquals("RUNNING", jdbc.queryForObject(
                "SELECT state FROM exercise_group WHERE id = ?", String.class, healthy));
        assertEquals(healthyRevision, (long) jdbc.queryForObject(
                "SELECT revision FROM exercise_group WHERE id = ?", Long.class, healthy),
                "健康组的 revision 与状态不得被失败组触及");

        // 无 GROUP-DEFAULT 特判：两组都按自身状态机流转
        org.bluesky.training.testsupport.V2FixtureFactory.seedPublishedSnapshot(jdbc, failing);
        jdbc.update("INSERT INTO workstation_terminal "
                        + "(id, name, terminal_type, exercise_group_id) VALUES (?, ?, ?, ?)",
                "PP-01-" + UUID.randomUUID(), "机长席", "PSEUDO_PILOT", failing);
        Map<String, Object> restart = transactionTemplate.execute(status ->
                exerciseGroupService.requestStart(
                        org.bluesky.training.common.CallerContext.terminal("PP-01", failing, "d"),
                        failing, jdbc.queryForObject(
                                "SELECT revision FROM exercise_group WHERE id = ?", Long.class, failing)));
        assertEquals("STARTING", restart.get("state"));
    }
}
