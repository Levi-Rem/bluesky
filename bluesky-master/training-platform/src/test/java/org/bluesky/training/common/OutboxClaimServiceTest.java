package org.bluesky.training.common;

import org.bluesky.training.TrainingPlatformApplication;
import org.bluesky.training.persistence.OutboxEventMapper;
import org.bluesky.training.persistence.OutboxEventRow;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P01：Outbox 批次认领。条件更新 + FOR UPDATE 等价于 SKIP LOCKED 的隔离效果；
 * MySQL Testcontainers 场景按 TDD 计划第 3 节另行执行。
 */
@SpringBootTest(classes = TrainingPlatformApplication.class)
@ActiveProfiles("test")
class OutboxClaimServiceTest {

    @Autowired
    private OutboxClaimService claimService;

    @Autowired
    private OutboxEventMapper outboxMapper;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String seedPendingEvent(String groupId) {
        String id = UUID.randomUUID().toString();
        transactionTemplate.execute(status -> {
            outboxMapper.insert(OutboxEventRow.businessEvent(
                    id, groupId, "instruction.updated", "{\"probe\":true}"));
            return null;
        });
        return id;
    }

    @Test
    void givenTwoWorkersWhenClaimingThenRowsAreNotDuplicated() throws Exception {
        String groupId = "group-claim-" + UUID.randomUUID();
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            ids.add(seedPendingEvent(groupId));
        }

        ExecutorService pool = Executors.newFixedThreadPool(2);
        Callable<List<String>> workerA = () -> claimService.claimBatch("worker-a", 200);
        Callable<List<String>> workerB = () -> claimService.claimBatch("worker-b", 200);
        List<String> claimed = new ArrayList<>();
        try {
            List<Future<List<String>>> futures = pool.invokeAll(java.util.Arrays.asList(workerA, workerB));
            for (Future<List<String>> future : futures) {
                claimed.addAll(future.get(30, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }

        Set<String> uniqueClaimed = new HashSet<>(claimed);
        assertEquals(claimed.size(), uniqueClaimed.size(), "同一行不能被两个 worker 重复认领");
        assertTrue(uniqueClaimed.containsAll(ids), "本用例的全部待发行应被认领: " + ids + " 实际 " + uniqueClaimed);
    }

    @Test
    void givenClaimedRowWhenMarkedSentThenStatusIsSent() {
        String groupId = "group-sent-" + UUID.randomUUID();
        String id = seedPendingEvent(groupId);
        List<String> claimed = claimService.claimBatch("worker-sent", 200);
        assertTrue(claimed.contains(id));

        claimService.markSent(id);

        OutboxEventRow row = outboxMapper.findById(id);
        assertEquals("SENT", row.getStatus());
        assertEquals(null, row.getClaimedBy());
    }

    @Test
    void givenFailedAttemptWhenRescheduledThenPendingAgainWithBackoff() {
        String groupId = "group-retry-" + UUID.randomUUID();
        String id = seedPendingEvent(groupId);
        claimService.claimBatch("worker-retry", 200);
        int attemptsBefore = outboxMapper.findById(id).getAttemptCount();

        claimService.reschedule(id, 30);

        OutboxEventRow row = outboxMapper.findById(id);
        assertEquals("PENDING", row.getStatus());
        assertEquals(attemptsBefore + 1, row.getAttemptCount());
        assertTrue(row.getNextAttemptAt() != null, "重试必须安排退避时间");
    }

    @Test
    void givenExhaustedEventWhenMarkedFailedThenStatusIsFailed() {
        String groupId = "group-fail-" + UUID.randomUUID();
        String id = seedPendingEvent(groupId);
        claimService.claimBatch("worker-fail", 200);

        claimService.markFailed(id);

        assertEquals("FAILED", outboxMapper.findById(id).getStatus());
    }

    @Test
    void givenWorkerDiesAfterClaimWhenLeaseExpiresThenAnotherWorkerCanReclaim() {
        String id = seedPendingEvent("group-stale-claim-" + UUID.randomUUID());
        assertTrue(claimService.claimBatch("dead-worker", 200).contains(id));
        jdbcTemplate.update("UPDATE outbox_event SET claimed_at = ? WHERE id = ?",
                java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(120)), id);

        List<String> reclaimed = claimService.claimBatch("replacement-worker", 200);

        assertTrue(reclaimed.contains(id));
        assertEquals("replacement-worker", outboxMapper.findById(id).getClaimedBy());
    }
}
