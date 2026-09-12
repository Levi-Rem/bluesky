package org.bluesky.training.aircraft;

import org.bluesky.training.adapter.SimulationGateway;
import org.bluesky.training.common.CallerContext;
import org.bluesky.training.common.V2DomainException;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * v1 删除门面（demo 桥）：单请求内完成 token 服务端签发/原子消费 → Saga 删除
 * 请求 → v1 网关同步删实体 → Adapter 确认回路同一收尾，不遗留
 * DELETE_REQUESTED 被 5 秒看门狗误判 DELETE_FAILED。
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "bluesky.adapter.v1-bridge-enabled=true",
        "bluesky.outbox.workers-enabled=false"})
class AircraftDeletionFacadeTest {

    @MockBean
    private SimulationGateway simulationGateway;

    @Autowired
    private AircraftDeletionFacade facade;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private String groupId;
    private String terminalId;
    private String aircraftId;
    private String callsign;

    private void newAircraft(String lifecycle) {
        groupId = "group-fcd-" + UUID.randomUUID();
        terminalId = "FCD-" + System.nanoTime();
        jdbc.update("INSERT INTO exercise_group (id, name, state, simulation_time_seconds) "
                + "VALUES (?, ?, 'RUNNING', 600)", groupId, "门面组");
        jdbc.update("INSERT INTO workstation_terminal (id, name, terminal_type, "
                + "exercise_group_id) VALUES (?, '机长席', 'PSEUDO_PILOT', ?)", terminalId, groupId);
        aircraftId = "ac-fcd-" + UUID.randomUUID();
        callsign = "FD" + System.nanoTime() % 100000;
        jdbc.update("INSERT INTO exercise_aircraft (id, exercise_group_id, assigned_terminal_id, "
                        + "callsign, aircraft_type, wake_category, origin, destination, "
                        + "heading_degrees, altitude_feet, speed_knots, lifecycle, "
                        + "active_callsign_key) VALUES (?, ?, ?, ?, 'A320', 'M', 'ZGGG', 'ZBAA', "
                        + "20, 8000, 250, ?, ?)",
                aircraftId, groupId, terminalId, callsign, lifecycle, callsign);
        jdbc.update("INSERT INTO aircraft_assignment (id, aircraft_id, terminal_id, current_key) "
                + "VALUES (?, ?, ?, ?)", UUID.randomUUID().toString(), aircraftId,
                terminalId, aircraftId);
    }

    private CallerContext caller() {
        return CallerContext.terminal(terminalId, groupId, null);
    }

    @Test
    void givenBridgeModeWhenV1DeleteThenSagaCompletesSynchronously() {
        newAircraft("ACTIVE");

        Map<String, Object> envelope = transactionTemplate.execute(status ->
                facade.deleteNow(caller(), aircraftId));

        assertEquals("DELETED", envelope.get("state"));
        assertEquals(Boolean.FALSE, envelope.get("adapterPending"));
        verify(simulationGateway).deleteAircraft(callsign);
        assertEquals("DELETED", jdbc.queryForObject(
                "SELECT lifecycle FROM exercise_aircraft WHERE id = ?", String.class, aircraftId));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM aircraft_assignment WHERE aircraft_id = ? "
                        + "AND ended_at IS NULL", Integer.class, aircraftId),
                "责任分配随确认事务结束");
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM exercise_aircraft WHERE id = ? "
                        + "AND active_callsign_key IS NOT NULL", Integer.class, aircraftId),
                "current_key（active_callsign_key）随删除清空");
    }

    @Test
    void givenPlannedAircraftWhenV1DeleteThenLocalCompletionWithoutGateway() {
        newAircraft("PLANNED");

        Map<String, Object> envelope = transactionTemplate.execute(status ->
                facade.deleteNow(caller(), aircraftId));

        assertEquals("DELETED", envelope.get("state"));
        verify(simulationGateway, never()).deleteAircraft(anyString());
    }

    @Test
    void givenAlreadyDeletedWhenV1DeleteAgainThenRejected() {
        newAircraft("ACTIVE");
        transactionTemplate.execute(status -> facade.deleteNow(caller(), aircraftId));

        // 已删除后重复点击：无当前责任席 → 403，而非再次触发网关删除
        V2DomainException failure = assertThrows(V2DomainException.class, () ->
                transactionTemplate.execute(status -> facade.deleteNow(caller(), aircraftId)));
        assertEquals("AIRCRAFT_NOT_ASSIGNED", failure.code());
        verify(simulationGateway).deleteAircraft(callsign);
    }

    @Test
    void givenForeignResponsibleTerminalWhenV1DeleteThenRejectedAtomically() {
        newAircraft("ACTIVE");
        CallerContext foreign = CallerContext.terminal("FCD-OTHER", groupId, null);

        V2DomainException failure = assertThrows(V2DomainException.class, () ->
                transactionTemplate.execute(status -> facade.deleteNow(foreign, aircraftId)));
        assertEquals("AIRCRAFT_NOT_ASSIGNED", failure.code());
        verify(simulationGateway, never()).deleteAircraft(anyString());
        assertEquals("ACTIVE", jdbc.queryForObject(
                "SELECT lifecycle FROM exercise_aircraft WHERE id = ?", String.class, aircraftId));
        assertEquals(403, failure.httpStatus());
    }
}
