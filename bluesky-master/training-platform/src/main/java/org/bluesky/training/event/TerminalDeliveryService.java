package org.bluesky.training.event;

import org.bluesky.training.persistence.BusinessEventMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/** P06：终端可靠投递窗口（详细设计 9.5.6/9.5.7）。 */
@Service
public class TerminalDeliveryService {

    public static final int RETAIN_MIN_COUNT = 10_000;
    public static final long RETAIN_MIN_MILLIS = 30 * 60 * 1000L;
    public static final int REPLAY_BATCH_LIMIT = 500;

    private final BusinessEventMapper mapper;

    public TerminalDeliveryService(BusinessEventMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> loadAfter(String terminalId, String epoch,
                                               long afterSequence, int limit) {
        return mapper.loadAfter(terminalId, epoch, afterSequence,
                Math.min(Math.max(1, limit), REPLAY_BATCH_LIMIT));
    }

    @Transactional(readOnly = true)
    public long maxDeliverySequence(String terminalId, String epoch) {
        return mapper.maxDeliverySequence(terminalId, epoch);
    }

    @Transactional(readOnly = true)
    public long oldestRetainedSequence(String terminalId, String epoch) {
        return mapper.oldestRetainedSequence(terminalId, epoch);
    }

    @Transactional(readOnly = true)
    public boolean eligibleForPurge(String terminalId, String epoch, long deliveryCount,
                                    long oldestAgeMillis) {
        // 两个条件都满足才允许清理（详细设计 9.5.6：任一未满足均不得删除）
        return deliveryCount > RETAIN_MIN_COUNT && oldestAgeMillis > RETAIN_MIN_MILLIS;
    }
}
