package org.bluesky.training.exercise;

import org.bluesky.training.TrainingPlatformApplication;
import org.bluesky.training.common.V2DomainException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** P05：持久化组仿真时钟（详细设计 4.2：非 RUNNING 不推进）。 */
@SpringBootTest(classes = TrainingPlatformApplication.class)
@ActiveProfiles("test")
class SimulationClockServiceTest {

    @Autowired
    private SimulationClockService clockService;

    @Autowired
    private JdbcTemplate jdbc;

    private String newGroup(String state, long simulationSeconds) {
        String groupId = "group-clock-" + UUID.randomUUID();
        jdbc.update("INSERT INTO exercise_group (id, name, state, simulation_time_seconds) "
                        + "VALUES (?, ?, ?, ?)",
                groupId, "时钟组", state, simulationSeconds);
        return groupId;
    }

    @Test
    void givenRunningGroupWhenAdvancedThenSimulationTimeIncreases() {
        String groupId = newGroup("RUNNING", 600);

        clockService.advanceFromFrame(groupId, 660);
        assertEquals(660L, clockService.currentTime(groupId));
    }

    @Test
    void givenPausedGroupWhenAdvancedThenRejected() {
        String groupId = newGroup("PAUSED", 600);

        V2DomainException failure = assertThrows(V2DomainException.class,
                () -> clockService.advanceFromFrame(groupId, 700));
        assertEquals("TRAINING_STATE_INVALID", failure.code());
        assertEquals(600L, clockService.currentTime(groupId), "暂停冻结仿真时间");
    }

    @Test
    void givenEndingGroupWhenAdvancedThenRejected() {
        String groupId = newGroup("ENDING", 100);
        assertThrows(V2DomainException.class, () -> clockService.advanceFromFrame(groupId, 200));
        assertEquals(100L, clockService.currentTime(groupId));
    }

    @Test
    void givenRunningGroupWhenFrameIsOlderThenTimeDoesNotGoBackwards() {
        String groupId = newGroup("RUNNING", 660);
        clockService.advanceFromFrame(groupId, 600);
        assertEquals(660L, clockService.currentTime(groupId), "仿真时间不得回退");
    }

    @Test
    void givenRunningGroupWhenFrameIsEqualThenRevisionStaysUntouched() {
        // 评审 C11：等值帧不得推进 revision，否则与生命周期动作乐观锁互相打架
        String groupId = newGroup("RUNNING", 660);
        long revisionBefore = revisionOf(groupId);
        clockService.advanceFromFrame(groupId, 660);
        assertEquals(revisionBefore, revisionOf(groupId), "等值帧不得推进 revision");
    }

    @Test
    void givenPausedGroupWhenActualPauseTimeArrivesThenClockAdoptsIt() {
        // 评审 C3/A5：PAUSED 采用 Adapter 返回的实际暂停时刻（详细设计 4.2.7）
        String groupId = newGroup("PAUSED", 600);
        clockService.freezeAtPause(groupId, 612.4);
        assertEquals(612L, clockService.currentTime(groupId), "实际暂停时刻必须落库");
    }

    @Test
    void givenPausedGroupWhenActualPauseTimeIsOlderThenClockDoesNotGoBackwards() {
        String groupId = newGroup("PAUSED", 600);
        clockService.freezeAtPause(groupId, 590.0);
        assertEquals(600L, clockService.currentTime(groupId), "暂停时刻不得回退");
    }

    @Test
    void givenRunningGroupWhenActualPauseTimeArrivesThenIgnored() {
        String groupId = newGroup("RUNNING", 600);
        clockService.freezeAtPause(groupId, 612.0);
        assertEquals(600L, clockService.currentTime(groupId), "仅 PAUSED 接受实际暂停时刻");
    }

    private long revisionOf(String groupId) {
        return jdbc.queryForObject("SELECT revision FROM exercise_group WHERE id = ?",
                Long.class, groupId);
    }
}
