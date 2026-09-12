package org.bluesky.training.aircraft;

import org.bluesky.training.TrainingPlatformApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** P07：版本化飞行计划——只增版本、旧版本只读（详细设计 5.2.8）。 */
@SpringBootTest(classes = TrainingPlatformApplication.class)
@ActiveProfiles("test")
class FlightPlanServiceTest {

    @Autowired
    private FlightPlanService flightPlanService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private String newAircraft() {
        String groupId = "group-fp-" + UUID.randomUUID();
        String aircraftId = "ac-" + UUID.randomUUID();
        jdbc.update("INSERT INTO exercise_group (id, name, state) VALUES (?, ?, 'READY')",
                groupId, "计划组");
        jdbc.update("INSERT INTO workstation_terminal (id, name, terminal_type, exercise_group_id) "
                        + "VALUES (?, '机长席', 'PSEUDO_PILOT', ?)", "PP-FP-" + groupId, groupId);
        jdbc.update("INSERT INTO exercise_aircraft (id, exercise_group_id, assigned_terminal_id, "
                        + "callsign, aircraft_type, wake_category, origin, destination, "
                        + "heading_degrees, altitude_feet, speed_knots, active_callsign_key) "
                        + "VALUES (?, ?, ?, ?, 'A320', 'M', 'ZGGG', 'ZBAA', 20, 8000, 250, ?)",
                aircraftId, groupId, "PP-FP-" + groupId,
                "FP" + System.nanoTime() % 100000,
                "FP" + System.nanoTime() % 100000);
        return aircraftId;
    }

    @Test
    void givenRouteChangeThenNewVersionAndOldVersionRemainReadOnly() {
        String aircraftId = newAircraft();

        Map<String, Object> v1 = transactionTemplate.execute(status ->
                flightPlanService.createVersion(aircraftId, "ZGGG", "ZBAA", "0042", "C",
                        32000, 280, Arrays.asList("ZGGG", "LMN", "ZBAA")));
        Map<String, Object> v2 = transactionTemplate.execute(status ->
                flightPlanService.createVersion(aircraftId, "ZGGG", "ZBAA", "0042", "C",
                        34000, 280, Arrays.asList("ZGGG", "ZBAA")));

        assertEquals(1, ((Number) v1.get("planVersion")).intValue());
        assertEquals(2, ((Number) v2.get("planVersion")).intValue());
        assertNotEquals(v1.get("id"), v2.get("id"));

        List<Map<String, Object>> versions = flightPlanService.listVersions(aircraftId);
        assertEquals(2, versions.size());
        assertEquals(2, ((List<?>) versions.get(0).get("legs")).size(), "v2 两条航段");
        assertEquals(3, ((List<?>) versions.get(1).get("legs")).size(), "v1 三条航段保持只读");

        // 一次事务内写计划与航段：旧版本航段数不因新版本改变
        assertEquals(3, jdbc.queryForObject(
                "SELECT COUNT(*) FROM flight_plan_leg WHERE flight_plan_id = ?",
                Integer.class, v1.get("id")));
    }

    @Test
    void givenCurrentVersionWhenCheckedWritableThenPasses() {
        String aircraftId = newAircraft();
        transactionTemplate.execute(status -> flightPlanService.createVersion(
                aircraftId, "ZGGG", "ZBAA", "0042", "C", 32000, 280,
                Arrays.asList("ZGGG", "ZBAA")));
        transactionTemplate.execute(status -> flightPlanService.createVersion(
                aircraftId, "ZGGG", "ZBAA", "0042", "C", 32000, 280,
                Arrays.asList("ZGGG", "LMN", "ZBAA")));

        org.bluesky.training.common.V2DomainException failure =
                org.junit.jupiter.api.Assertions.assertThrows(
                        org.bluesky.training.common.V2DomainException.class,
                        () -> flightPlanService.assertVersionWritable(aircraftId, 1));
        assertEquals(409, failure.httpStatus());
        flightPlanService.assertVersionWritable(aircraftId, 2);
    }

    @Test
    void givenRouteNotEndingAtDestinationWhenCreatedThenRejected() {
        String aircraftId = newAircraft();
        jdbc.update("INSERT INTO flight_plan (id, aircraft_id, plan_version, origin, destination, "
                        + "route_text) VALUES (?, ?, 1, 'ZGGG', 'ZBAA', 'ZGGG ZBAA')",
                java.util.UUID.randomUUID().toString(), aircraftId);
        org.bluesky.training.common.V2DomainException failure =
                org.junit.jupiter.api.Assertions.assertThrows(
                        org.bluesky.training.common.V2DomainException.class,
                        () -> transactionTemplate.execute(status ->
                                flightPlanService.createVersion(aircraftId, "ZGGG", "ZBAA",
                                        "0042", "C", 32000, 280,
                                        Arrays.asList("ZGGG", "LMN"))));
        assertTrue(failure.getMessage().contains("落地机场"));
    }
}
