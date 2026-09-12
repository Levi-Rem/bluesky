package org.bluesky.training.exercise;

import org.bluesky.training.TrainingPlatformApplication;
import org.bluesky.training.adapter.SimulationGateway;
import org.bluesky.training.common.CallerContext;
import org.bluesky.training.testsupport.V2FixtureFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;

/**
 * 迁移桥回退（demo：v1-bridge-enabled=true 且 outbox workers 关闭）：
 * 生命周期动作必须同步经 v1 网关执行并直接确认终态，不得滞留过渡态。
 */
@SpringBootTest(classes = TrainingPlatformApplication.class)
@ActiveProfiles("test")
@TestPropertySource(properties = "bluesky.adapter.v1-bridge-enabled=true")
class ExerciseGroupBridgeFallbackTest {

    @Autowired
    private ExerciseGroupService exerciseGroupService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @MockBean
    private SimulationGateway simulationGateway;

    private String newGroup(String state) {
        String groupId = "group-bridge-" + UUID.randomUUID();
        jdbc.update("INSERT INTO exercise_group (id, name, state) VALUES (?, ?, ?)",
                groupId, "桥回退组", state);
        if ("READY".equals(state)) {
            V2FixtureFactory.seedPublishedSnapshot(jdbc, groupId);
            jdbc.update("INSERT INTO workstation_terminal "
                            + "(id, name, terminal_type, exercise_group_id) VALUES (?, ?, ?, ?)",
                    "terminal-" + UUID.randomUUID(), "机长席", "PSEUDO_PILOT", groupId);
        }
        return groupId;
    }

    private long revisionOf(String groupId) {
        return jdbc.queryForObject("SELECT revision FROM exercise_group WHERE id = ?",
                Long.class, groupId);
    }

    private String stateOf(String groupId) {
        return jdbc.queryForObject("SELECT state FROM exercise_group WHERE id = ?",
                String.class, groupId);
    }

    @Test
    void givenBridgeStartWhenReadyThenEngineStartedAndGroupRunningImmediately() {
        String groupId = newGroup("READY");

        Map<String, Object> envelope = transactionTemplate.execute(status ->
                exerciseGroupService.requestStart(
                        CallerContext.terminal("PP-01", groupId, "digest"),
                        groupId, revisionOf(groupId)));

        assertEquals("RUNNING", envelope.get("state"), "桥模式 START 不得滞留 STARTING");
        assertEquals("RUNNING", stateOf(groupId));
        verify(simulationGateway).start();
    }

    @Test
    void givenBridgePauseAndResumeWhenRunningThenGatewaySynchronizedAndConfirmed() {
        String groupId = newGroup("RUNNING");
        jdbc.update("INSERT INTO workstation_terminal "
                        + "(id, name, terminal_type, exercise_group_id) VALUES (?, ?, ?, ?)",
                "terminal-" + UUID.randomUUID(), "机长席", "PSEUDO_PILOT", groupId);
        CallerContext terminal = CallerContext.terminal("PP-01", groupId, "digest");

        Map<String, Object> paused = transactionTemplate.execute(status ->
                exerciseGroupService.requestPause(terminal, groupId, revisionOf(groupId)));
        assertEquals("PAUSED", paused.get("state"), "桥模式 PAUSE 不得滞留 PAUSING");
        assertEquals("PAUSED", stateOf(groupId));
        verify(simulationGateway).pause();

        Map<String, Object> resumed = transactionTemplate.execute(status ->
                exerciseGroupService.requestResume(terminal, groupId, revisionOf(groupId)));
        assertEquals("RUNNING", resumed.get("state"), "桥模式 RESUME 不得滞留 RESUMING");
        assertEquals("RUNNING", stateOf(groupId));
        verify(simulationGateway).resume();
    }

    @Test
    void givenFacadeStartWhenGroupAlreadyRunningThenEngineResumedForSelfHealing() {
        String originalState = stateOf("GROUP-DEFAULT");
        try {
            jdbc.update("UPDATE exercise_group SET state = 'RUNNING' WHERE id = 'GROUP-DEFAULT'");

            exerciseGroupService.start("GROUP-DEFAULT");

            assertEquals("RUNNING", stateOf("GROUP-DEFAULT"));
            verify(simulationGateway).resume();
        } finally {
            jdbc.update("UPDATE exercise_group SET state = ? WHERE id = 'GROUP-DEFAULT'",
                    originalState);
        }
    }
}
