package org.bluesky.training.common;

import org.bluesky.training.persistence.OutboxEventMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collections;
import java.util.List;

/** P01：Outbox 批次认领（SKIP LOCKED 的等价条件更新实现）。 */
@Service
public class OutboxClaimService {

    private static final long CLAIM_LEASE_SECONDS = 60;

    private final OutboxEventMapper mapper;

    public OutboxClaimService(OutboxEventMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional
    public List<String> claimBatch(String workerId, int limit) {
        return claimBatchInternal(workerId, limit, null);
    }

    @Transactional
    public List<String> claimBatchForKind(String workerId, int limit, String outboxKind) {
        return claimBatchInternal(workerId, limit, outboxKind);
    }

    private List<String> claimBatchInternal(String workerId, int limit, String outboxKind) {
        // worker 在网络调用或进程退出期间崩溃时，租约到期后必须允许其他 worker 接管。
        mapper.releaseExpiredClaims(Timestamp.from(
                Instant.now().minusSeconds(CLAIM_LEASE_SECONDS)));
        List<String> candidates = outboxKind == null
                ? mapper.findClaimableIds(limit)
                : mapper.findClaimableIdsByKind(outboxKind, limit);
        if (candidates == null || candidates.isEmpty()) {
            return Collections.emptyList();
        }
        mapper.claim(workerId, candidates);
        return mapper.findClaimedByWorker(workerId, candidates);
    }

    @Transactional
    public void markSent(String id) {
        mapper.markSent(id);
    }

    @Transactional
    public void confirm(String id) {
        mapper.confirm(id);
    }

    @Transactional
    public void reschedule(String id, int delaySeconds) {
        mapper.reschedule(id, Timestamp.from(Instant.now().plusSeconds(Math.max(0, delaySeconds))));
    }

    @Transactional
    public void markFailed(String id) {
        mapper.markFailed(id);
    }

    @Transactional
    public void recordFailure(String id, int nextAttempt, int maxAttempts, Timestamp retryAt) {
        mapper.reschedule(id, retryAt);
        if (nextAttempt >= maxAttempts) {
            mapper.markFailed(id);
        }
    }
}
