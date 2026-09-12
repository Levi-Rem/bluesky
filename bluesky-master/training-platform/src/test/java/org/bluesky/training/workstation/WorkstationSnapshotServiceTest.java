package org.bluesky.training.workstation;

import org.bluesky.training.TrainingPlatformApplication;
import org.bluesky.training.event.BusinessEventService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Collections;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** P06：bootstrap 与变更同事务读取（详细设计 9.5.4：快照与增量无窗口）。 */
@SpringBootTest(classes = TrainingPlatformApplication.class)
@ActiveProfiles("test")
class WorkstationSnapshotServiceTest {

    @Autowired
    private WorkstationSnapshotService snapshotService;

    @Autowired
    private BusinessEventService eventService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    void givenConcurrentMutationWhenBootstrappingThenSnapshotSequenceClosesTheGap() {
        String groupId = "group-boot-" + UUID.randomUUID();
        String terminalId = "PP-BOOT-" + System.nanoTime();
        jdbc.update("INSERT INTO exercise_group (id, name, state) VALUES (?, ?, 'RUNNING')",
                groupId, "引导组");
        jdbc.update("INSERT INTO workstation_terminal (id, name, terminal_type, exercise_group_id) "
                + "VALUES (?, '机长席', 'PSEUDO_PILOT', ?)", terminalId, groupId);

        // 同一事务内：先追加事件，再 bootstrap —— 快照序号必须覆盖这些事件
        Map<String, Object> snapshot = transactionTemplate.execute(status -> {
            for (int i = 0; i < 3; i++) {
                eventService.append(groupId, "instruction.updated", "{}",
                        Collections.singletonList(terminalId));
            }
            return snapshotService.bootstrap(terminalId, groupId);
        });

        long snapshotSequence = ((Number) snapshot.get("snapshotSequence")).longValue();
        Long maxDelivered = jdbc.queryForObject(
                "SELECT MAX(delivery_sequence) FROM terminal_event_delivery WHERE terminal_id = ?",
                Long.class, terminalId);
        assertEquals(maxDelivered, snapshotSequence,
                "快照序号必须闭合同事务内事件（无窗口）");
        assertEquals("RUNNING", snapshot.get("state"));
        assertEquals(3L, ((Number) snapshot.get("deliveries")).longValue());
        assertTrue(snapshot.get("streamEpoch") != null);
        assertTrue(snapshot.containsKey("aircraft"));
        assertTrue(snapshot.containsKey("assignments"));
        assertTrue(snapshot.containsKey("instructions"));
        assertTrue(snapshot.containsKey("scripts"));
        assertTrue(snapshot.containsKey("messages"));
        assertTrue(snapshot.containsKey("displayProfiles"));
        assertTrue(snapshot.containsKey("latestDynamicFrame"));
    }

    @Test
    void givenNoEventsWhenBootstrappingThenSnapshotSequenceIsZero() {
        String groupId = "group-boot0-" + UUID.randomUUID();
        String terminalId = "PP-BOOT0-" + System.nanoTime();
        jdbc.update("INSERT INTO exercise_group (id, name, state) VALUES (?, ?, 'READY')",
                groupId, "空引导组");
        jdbc.update("INSERT INTO workstation_terminal (id, name, terminal_type, exercise_group_id) "
                + "VALUES (?, '机长席', 'PSEUDO_PILOT', ?)", terminalId, groupId);

        Map<String, Object> snapshot = snapshotService.bootstrap(terminalId, groupId);
        assertEquals(0L, ((Number) snapshot.get("snapshotSequence")).longValue());
        assertEquals("READY", snapshot.get("state"));
    }
}
