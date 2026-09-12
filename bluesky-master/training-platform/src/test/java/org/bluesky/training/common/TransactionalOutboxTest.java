package org.bluesky.training.common;

import org.bluesky.training.TrainingPlatformApplication;
import org.bluesky.training.persistence.OutboxEventRow;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** P01：Outbox 与业务变更同事务（详细设计 3.3.8 / 5.0.5）。 */
@SpringBootTest(classes = TrainingPlatformApplication.class)
@ActiveProfiles("test")
class TransactionalOutboxTest {

    @Autowired
    private TransactionalOutboxService outboxService;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    private String newEventId() {
        return java.util.UUID.randomUUID().toString();
    }

    @Test
    void givenBusinessRollbackWhenEnqueueThenNoOutboxRemains() {
        String eventId = newEventId();
        try {
            transactionTemplate.execute(status -> {
                outboxService.enqueueBusinessEvent(OutboxEventRow.businessEvent(
                        eventId, "group-tx-1", "group.state.changed", "{\"state\":\"STARTING\"}"));
                throw new IllegalStateException("业务回滚");
            });
        } catch (IllegalStateException expected) {
            // 预期回滚
        }

        Integer remaining = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE id = ?", Integer.class, eventId);
        assertEquals(0, remaining, "业务事务回滚时 Outbox 必须一起消失");
    }

    @Test
    void givenCommittedBusinessWhenEnqueueThenOutboxIsPersisted() {
        String eventId = newEventId();
        transactionTemplate.execute(status -> {
            outboxService.enqueueBusinessEvent(OutboxEventRow.businessEvent(
                    eventId, "group-tx-2", "instruction.updated", "{\"id\":\"i-1\"}"));
            return null;
        });

        Integer persisted = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE id = ? AND status = 'PENDING'", Integer.class, eventId);
        assertEquals(1, persisted, "业务提交后 Outbox 必须持久化为 PENDING");
    }

    @Test
    void givenEnqueueOutsideTransactionWhenCalledThenRejected() {
        assertThrows(IllegalStateException.class,
                () -> outboxService.enqueueAdapterAction(OutboxEventRow.adapterAction(
                        newEventId(), "group-tx-3", "AIRCRAFT_APPLY", "{}")),
                "Outbox 只允许在业务事务内写入");
    }
}
