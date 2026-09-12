package org.bluesky.training.common;

import org.bluesky.training.persistence.OutboxEventMapper;
import org.bluesky.training.persistence.OutboxEventRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** P01：Outbox 只允许与业务变更处于同一事务（详细设计 3.3.8 / 5.0.5）。 */
@Service
public class TransactionalOutboxService {

    private final OutboxEventMapper mapper;

    public TransactionalOutboxService(OutboxEventMapper mapper) {
        this.mapper = mapper;
    }

    public void enqueueBusinessEvent(OutboxEventRow row) {
        requireTransaction();
        requireKind(row, OutboxEventRow.KIND_BUSINESS_EVENT);
        mapper.insert(row);
    }

    public void enqueueAdapterAction(OutboxEventRow row) {
        requireTransaction();
        requireKind(row, OutboxEventRow.KIND_ADAPTER_ACTION);
        mapper.insert(row);
    }

    public boolean retryAdapterAction(String instanceId, String key) {
        requireTransaction();
        return mapper.retryAdapterAction(instanceId, key) > 0;
    }

    public void terminateInstructionApply(String instructionId) {
        requireTransaction();
        mapper.terminateInstructionApply("instruction:" + instructionId);
    }

    public boolean hasAdapterAction(String instance, String key) { return mapper.countAdapterAction(instance,key)>0; }

    private void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Outbox 必须与业务写入处于同一事务内调用");
        }
    }

    private void requireKind(OutboxEventRow row, String expectedKind) {
        if (row == null || !expectedKind.equals(row.getOutboxKind())) {
            throw new IllegalArgumentException("Outbox 行类型必须是 " + expectedKind);
        }
    }
}
