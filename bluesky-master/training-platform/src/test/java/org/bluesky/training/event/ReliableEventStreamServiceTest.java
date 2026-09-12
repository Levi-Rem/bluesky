package org.bluesky.training.event;

import org.bluesky.training.TrainingPlatformApplication;
import org.bluesky.training.common.V2DomainException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** P06：续传、重复游标、错误终端、窗口过期与慢消费者边界（详细设计 9.5.6/9.5.7）。 */
@SpringBootTest(classes = TrainingPlatformApplication.class)
@ActiveProfiles("test")
class ReliableEventStreamServiceTest {

    @Autowired
    private ReliableEventStreamService streamService;

    @Autowired
    private BusinessEventService eventService;

    @Autowired
    private StreamEpochService epochService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private String newGroupWithEvents(String terminalId, int count) {
        String groupId = "group-sse-" + UUID.randomUUID();
        jdbc.update("INSERT INTO exercise_group (id, name, state) VALUES (?, ?, 'RUNNING')",
                groupId, "SSE 组");
        for (int i = 0; i < count; i++) {
            final String payload = "{\"i\":" + i + "}";
            transactionTemplate.execute(status -> eventService.append(groupId,
                    "instruction.updated", payload,
                    Collections.singletonList(terminalId)));
        }
        return groupId;
    }

    @Test
    void givenCursorWhenReplayedThenOnlyLaterEventsArriveInOrder() {
        String terminalId = "PP-SSE-1-" + System.nanoTime();
        newGroupWithEvents(terminalId, 5);
        String epoch = epochService.currentEpoch();

        String cursor = EventCursorCodec.encode(terminalId, epoch, 2);
        long after = streamService.prepareCursor(terminalId, cursor);
        List<Map<String, Object>> replay = streamService.replayAfterCursor(terminalId, after, 100);

        assertEquals(3, replay.size());
        for (int i = 0; i < replay.size(); i++) {
            assertEquals(3 + i, ((Number) replay.get(i).get("deliverySequence")).longValue());
            assertTrue(String.valueOf(replay.get(i).get("eventId")).startsWith(terminalId + ":"));
        }
    }

    @Test
    void givenDuplicateCursorWhenReplayedAgainThenSameEventsIdempotent() {
        String terminalId = "PP-SSE-2-" + System.nanoTime();
        newGroupWithEvents(terminalId, 3);
        String epoch = epochService.currentEpoch();
        long after = streamService.prepareCursor(terminalId,
                EventCursorCodec.encode(terminalId, epoch, 1));

        List<Map<String, Object>> first = streamService.replayAfterCursor(terminalId, after, 100);
        List<Map<String, Object>> second = streamService.replayAfterCursor(terminalId, after, 100);

        assertEquals(first.size(), second.size());
        for (int i = 0; i < first.size(); i++) {
            assertEquals(first.get(i).get("eventId"), second.get(i).get("eventId"));
        }
    }

    @Test
    void givenForeignTerminalCursorWhenPreparedThen403() {
        String terminalId = "PP-SSE-3-" + System.nanoTime();
        newGroupWithEvents(terminalId, 1);
        String epoch = epochService.currentEpoch();

        V2DomainException failure = assertThrows(V2DomainException.class,
                () -> streamService.prepareCursor("PP-OTHER",
                        EventCursorCodec.encode(terminalId, epoch, 1)));
        assertEquals(403, failure.httpStatus());
        assertEquals("TRUSTED_IDENTITY_REJECTED", failure.code());
    }

    @Test
    void givenCursorOutsideWindowWhenPreparedThen409() {
        String terminalId = "PP-SSE-4-" + System.nanoTime();
        newGroupWithEvents(terminalId, 5);
        String epoch = epochService.currentEpoch();
        // 模拟窗口清理：删除前 4 条投递，保留第 5 条 → 最早保留序号 5
        jdbc.update("DELETE FROM terminal_event_delivery WHERE terminal_id = ? "
                + "AND delivery_sequence <= 4", terminalId);

        V2DomainException failure = assertThrows(V2DomainException.class,
                () -> streamService.prepareCursor(terminalId,
                        EventCursorCodec.encode(terminalId, epoch, 1)));
        assertEquals(409, failure.httpStatus());
        assertEquals("EVENT_CURSOR_EXPIRED", failure.code(),
                "窗口外重连必须返回 409 让客户端重新 bootstrap");
    }

    @Test
    void givenEpochRebuiltWhenPreparedThen409() {
        String terminalId = "PP-SSE-5-" + System.nanoTime();
        newGroupWithEvents(terminalId, 1);
        String oldEpoch = epochService.currentEpoch();

        epochService.rebuildProjection();

        V2DomainException failure = assertThrows(V2DomainException.class,
                () -> streamService.prepareCursor(terminalId,
                        EventCursorCodec.encode(terminalId, oldEpoch, 1)));
        assertEquals("EVENT_CURSOR_EXPIRED", failure.code());
    }

    @Test
    void givenSlowConsumerLimitsWhenCheckedThenMatchDesign() {
        assertEquals(2000, ReliableEventStreamService.SLOW_CONSUMER_EVENT_LIMIT);
        assertEquals(10 * 1024 * 1024, ReliableEventStreamService.SLOW_CONSUMER_BYTES_LIMIT);
        assertEquals(10_000, TerminalDeliveryService.RETAIN_MIN_COUNT);
    }

    @Test
    void givenSameInstanceWhenEpochReadTwiceThenUnchangedUntilRebuild() {
        assertEquals(epochService.currentEpoch(), epochService.currentEpoch());
    }
}
