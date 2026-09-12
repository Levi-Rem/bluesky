package org.bluesky.training.event;

import org.bluesky.training.TrainingPlatformApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** P06：可靠事件追加、定向扇出与并发序列（详细设计 9.5.1）。 */
@SpringBootTest(classes = TrainingPlatformApplication.class)
@ActiveProfiles("test")
class BusinessEventServiceTest {

    @Autowired
    private BusinessEventService eventService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private String newGroup() {
        String groupId = "group-evt-" + UUID.randomUUID();
        jdbc.update("INSERT INTO exercise_group (id, name, state) VALUES (?, ?, 'RUNNING')",
                groupId, "事件组");
        return groupId;
    }

    @Test
    void givenTargetedMessageWhenAppendedThenOnlyTargetTerminalReceives() {
        String groupId = newGroup();
        String target = "PP-T1-" + System.nanoTime();
        String other = "PP-T2-" + System.nanoTime();

        transactionTemplate.execute(status -> eventService.append(groupId,
                "terminal.message.received", "{\"body\":\"hello\"}",
                Collections.singletonList(target)));

        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM terminal_event_delivery WHERE terminal_id = ?",
                Long.class, target));
        assertEquals(0L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM terminal_event_delivery WHERE terminal_id = ?",
                Long.class, other), "正文只投递目标终端");
    }

    @Test
    void givenConcurrentEventsThenGroupAndDeliverySequencesRemainStrictlyIncreasing()
            throws Exception {
        String groupId = newGroup();
        String terminalId = "PP-CONC-" + System.nanoTime();
        int threads = 4;
        int perThread = 10;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            tasks.add(() -> {
                for (int i = 0; i < perThread; i++) {
                    transactionTemplate.execute(status -> eventService.append(groupId,
                            "instruction.updated", "{}",
                            Collections.singletonList(terminalId)));
                }
                return null;
            });
        }
        try {
            for (Future<Object> future : pool.invokeAll(tasks)) {
                future.get(60, java.util.concurrent.TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        List<Long> groupSequences = jdbc.queryForList(
                "SELECT group_sequence FROM business_event WHERE exercise_group_id = ? "
                        + "ORDER BY group_sequence", Long.class, groupId);
        assertEquals(threads * perThread, groupSequences.size());
        for (int i = 1; i < groupSequences.size(); i++) {
            assertEquals(groupSequences.get(i - 1) + 1, (long) groupSequences.get(i),
                    "组序号必须严格连续递增");
        }

        List<Long> deliverySequences = jdbc.queryForList(
                "SELECT delivery_sequence FROM terminal_event_delivery WHERE terminal_id = ? "
                        + "ORDER BY delivery_sequence", Long.class, terminalId);
        assertEquals(threads * perThread, deliverySequences.size());
        for (int i = 1; i < deliverySequences.size(); i++) {
            assertEquals(deliverySequences.get(i - 1) + 1, (long) deliverySequences.get(i),
                    "终端投递序号必须严格连续递增");
        }
    }

    @Test
    void givenRepeatedAppendsWhenGroupSequenceReadBackThenMonotonic() {
        String groupId = newGroup();
        long first = (Long) transactionTemplate.execute(status -> eventService.append(
                groupId, "a", "{}", Collections.emptyList())).get("groupSequence");
        long second = (Long) transactionTemplate.execute(status -> eventService.append(
                groupId, "b", "{}", Collections.emptyList())).get("groupSequence");

        assertTrue(second > first);
    }

    @Test
    void givenFanOutWhenAppendedThenOneBusinessEventAndOneDeliveryPerTerminal() {
        String groupId = newGroup();
        String firstTerminal = "PP-FAN-1-" + System.nanoTime();
        String secondTerminal = "PP-FAN-2-" + System.nanoTime();

        List<Map<String, Object>> result = transactionTemplate.execute(status ->
                eventService.appendFanOut(groupId, "group.state.changed", "{}",
                        java.util.Arrays.asList(firstTerminal, secondTerminal)));

        assertEquals(1, result.size(), "一个逻辑事件只能分配一个 groupSequence");
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM business_event WHERE exercise_group_id = ?",
                Long.class, groupId));
        assertEquals(2L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM terminal_event_delivery d JOIN business_event b "
                        + "ON b.id = d.business_event_id WHERE b.exercise_group_id = ?",
                Long.class, groupId));
    }

    @Test
    void givenEntityAndSimulationTimeWhenAppendedThenEnvelopeKeepsOriginalValues() {
        String groupId = newGroup();
        String terminalId = "PP-FIELDS-" + System.nanoTime();
        transactionTemplate.execute(status -> eventService.append(
                groupId, "aircraft.updated", "aircraft-1", "{}", 123.5,
                Collections.singletonList(terminalId)));

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT entity_id, simulation_time_seconds, system_time_utc "
                        + "FROM business_event WHERE exercise_group_id = ?", groupId);
        assertEquals("aircraft-1", row.get("ENTITY_ID"));
        assertEquals(123.5, ((Number) row.get("SIMULATION_TIME_SECONDS")).doubleValue(), 0.001);
        assertTrue(row.get("SYSTEM_TIME_UTC") != null);
    }
}
