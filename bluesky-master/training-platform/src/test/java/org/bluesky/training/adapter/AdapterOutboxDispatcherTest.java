package org.bluesky.training.adapter;

import org.bluesky.training.TrainingPlatformApplication;
import org.bluesky.training.common.OutboxClaimService;
import org.bluesky.training.common.TransactionalOutboxService;
import org.bluesky.training.persistence.OutboxEventMapper;
import org.bluesky.training.persistence.OutboxEventRow;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** P04：Adapter Outbox 派发：成功 SENT、失败退避、耗尽 FAILED、业务事件不被派发。 */
@SpringBootTest(classes = TrainingPlatformApplication.class)
@ActiveProfiles("test")
class AdapterOutboxDispatcherTest {

    @Autowired
    private AdapterOutboxDispatcher dispatcher;

    @Autowired
    private TransactionalOutboxService outboxService;

    @Autowired
    private OutboxClaimService claimService;

    @Autowired
    private OutboxEventMapper outboxMapper;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbc;

    private String seedAdapterAction() {
        String id = UUID.randomUUID().toString();
        String groupId = "group-disp-" + UUID.randomUUID();
        String terminalId = "terminal-disp-" + UUID.randomUUID();
        String aircraftId = "aircraft-disp-" + UUID.randomUUID();
        jdbc.update("INSERT INTO exercise_group (id, name, state) VALUES (?, '派发组', 'RUNNING')",
                groupId);
        jdbc.update("INSERT INTO workstation_terminal (id, name, terminal_type, exercise_group_id) "
                + "VALUES (?, '派发席', 'PSEUDO_PILOT', ?)", terminalId, groupId);
        jdbc.update("INSERT INTO exercise_aircraft (id, exercise_group_id, assigned_terminal_id, "
                        + "callsign, aircraft_type, wake_category, origin, destination, "
                        + "heading_degrees, altitude_feet, speed_knots, lifecycle, active_callsign_key) "
                        + "VALUES (?, ?, ?, ?, 'A320', 'M', 'ZGGG', 'ZBAA', 0, 8000, 250, "
                        + "'CREATE_REQUESTED', ?)", aircraftId, groupId, terminalId,
                "DA" + System.nanoTime() % 100000, "DA" + System.nanoTime() % 100000);
        transactionTemplate.execute(status -> {
            outboxService.enqueueAdapterAction(OutboxEventRow.adapterAction(
                    id, groupId, "AIRCRAFT_APPLY",
                    "{\"aircraftId\":\"" + aircraftId + "\"}"));
            return null;
        });
        return id;
    }

    @Test
    void givenSuccessfulSendWhenDispatchedThenMarkedSent() {
        String id = seedAdapterAction();
        AtomicInteger sends = new AtomicInteger();

        dispatcher.dispatchPending("worker-disp", action -> {
            sends.incrementAndGet();
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("messageType", "AIRCRAFT_APPLIED");
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("accepted", true);
            response.put("payload", payload);
            return response;
        }, 10);

        assertEquals("CONFIRMED", outboxMapper.findById(id).getStatus());
        assertEquals(1, sends.get());
    }

    @Test
    void givenFailingSendWhenDispatchedThenRescheduledWithBackoff() {
        String id = seedAdapterAction();

        dispatcher.dispatchPending("worker-fail", action -> {
            throw new AdapterProtocolException("ADAPTER_ACK_TIMEOUT", "无应答");
        }, 10);

        OutboxEventRow row = outboxMapper.findById(id);
        assertEquals("PENDING", row.getStatus());
        assertEquals(1, row.getAttemptCount());
        assertTrue(row.getNextAttemptAt() != null, "失败必须安排退避时间");
    }

    @Test
    void givenExhaustedAttemptsWhenDispatchedThenMarkedFailed() {
        String id = seedAdapterAction();
        transactionTemplate.execute(status -> {
            for (int i = 0; i < 7; i++) {
                claimService.claimBatch("worker-exhaust", 200);
                claimService.markSent(id);
                outboxMapper.reschedule(id, java.sql.Timestamp.from(
                        java.time.Instant.now().minusSeconds(60)));
            }
            return null;
        });

        dispatcher.dispatchPending("worker-final", action -> {
            throw new AdapterProtocolException("ADAPTER_ACK_TIMEOUT", "仍无应答");
        }, 10);

        OutboxEventRow row = outboxMapper.findById(id);
        assertEquals("FAILED", row.getStatus());
        assertEquals(8, row.getAttemptCount());
    }

    @Test
    void givenBusinessEventRowsWhenDispatchedThenNotSentViaAdapter() {
        String businessId = UUID.randomUUID().toString();
        transactionTemplate.execute(status -> {
            outboxService.enqueueBusinessEvent(OutboxEventRow.businessEvent(
                    businessId, "group-disp-biz", "instruction.updated", "{}"));
            return null;
        });
        AtomicInteger sends = new AtomicInteger();

        dispatcher.dispatchPending("worker-biz", action -> {
            sends.incrementAndGet();
            return new LinkedHashMap<>();
        }, 10);

        assertEquals("PENDING", outboxMapper.findById(businessId).getStatus(),
                "业务事件不由 Adapter 派发器发送");
    }

    @Test
    void givenBusinessRejectionWhenDispatchedThenConfirmedWithoutRetry() {
        String id = seedAdapterAction();

        dispatcher.dispatchPending("worker-rejected", action -> {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("messageType", "AIRCRAFT_APPLIED");
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("accepted", false);
            payload.put("code", "AIRCRAFT_NOT_FOUND");
            response.put("payload", payload);
            return response;
        }, 10);

        OutboxEventRow row = outboxMapper.findById(id);
        assertEquals("CONFIRMED", row.getStatus());
        assertEquals(0, row.getAttemptCount(), "确定性业务拒绝不得重试");
    }

    @Test
    void givenUnrelatedAckWhenDispatchedThenActionIsNotFalselyConfirmed() {
        String id = seedAdapterAction();

        dispatcher.dispatchPending("worker-wrong-ack", action -> {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("messageType", "HELLO_ACK");
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("accepted", true);
            response.put("payload", payload);
            return response;
        }, 10);

        OutboxEventRow row = outboxMapper.findById(id);
        assertEquals("PENDING", row.getStatus());
        assertEquals(1, row.getAttemptCount());
    }

    @Test
    void givenStartingAircraftRejectedThenGroupCompensatesAndEngineStops() {
        String groupId = "group-start-reject-" + UUID.randomUUID();
        String terminalId = "terminal-start-reject-" + UUID.randomUUID();
        String aircraftId = "aircraft-start-reject-" + UUID.randomUUID();
        String engineId = UUID.randomUUID().toString();
        String outboxId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO exercise_group (id, name, state) VALUES (?, '启动拒绝组', 'STARTING')",
                groupId);
        jdbc.update("INSERT INTO workstation_terminal (id, name, terminal_type, exercise_group_id) "
                + "VALUES (?, '启动席', 'PSEUDO_PILOT', ?)", terminalId, groupId);
        jdbc.update("INSERT INTO engine_instance (id, exercise_group_id, control_endpoint, "
                        + "state_endpoint, state) VALUES (?, ?, 'tcp://127.0.0.1:11', "
                        + "'tcp://127.0.0.1:12', 'CONNECTED')", engineId, groupId);
        jdbc.update("UPDATE exercise_group SET engine_instance_id = ? WHERE id = ?", engineId, groupId);
        jdbc.update("INSERT INTO exercise_aircraft (id, exercise_group_id, assigned_terminal_id, "
                        + "callsign, aircraft_type, wake_category, origin, destination, "
                        + "heading_degrees, altitude_feet, speed_knots, lifecycle, active_callsign_key) "
                        + "VALUES (?, ?, ?, 'SRJ1', 'A320', 'M', 'ZGGG', 'ZBAA', 0, 8000, 250, "
                        + "'CREATE_REQUESTED', 'SRJ1')", aircraftId, groupId, terminalId);
        transactionTemplate.execute(status -> {
            outboxService.enqueueAdapterAction(OutboxEventRow.adapterAction(
                    outboxId, groupId, "AIRCRAFT_APPLY",
                    "{\"aircraftId\":\"" + aircraftId + "\"}")
                    .routeTo(engineId, "req-" + outboxId, aircraftId));
            return null;
        });

        dispatcher.dispatchPending("worker-start-reject", action -> {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("messageType", "AIRCRAFT_APPLIED");
            response.put("payload", mapOf("accepted", false,
                    "code", "PERFORMANCE_LIMIT", "message", "性能超限"));
            return response;
        }, 10);

        assertEquals("CREATE_FAILED", jdbc.queryForObject(
                "SELECT lifecycle FROM exercise_aircraft WHERE id = ?", String.class, aircraftId));
        assertEquals("READY", jdbc.queryForObject(
                "SELECT state FROM exercise_group WHERE id = ?", String.class, groupId));
        assertEquals("STOPPED", jdbc.queryForObject(
                "SELECT state FROM engine_instance WHERE id = ?", String.class, engineId));
        assertEquals("CONFIRMED", outboxMapper.findById(outboxId).getStatus());
    }

    private static Map<String, Object> mapOf(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return map;
    }
}
