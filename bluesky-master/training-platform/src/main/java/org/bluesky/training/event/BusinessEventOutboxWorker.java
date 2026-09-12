package org.bluesky.training.event;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** 生产轮询接线；测试配置禁用以保持用例确定性。 */
@Component
@ConditionalOnProperty(name = "bluesky.outbox.workers-enabled",
        havingValue = "true", matchIfMissing = true)
public class BusinessEventOutboxWorker {

    private final BusinessEventOutboxDispatcher dispatcher;
    private final String workerId = "business-event-" + UUID.randomUUID();

    public BusinessEventOutboxWorker(BusinessEventOutboxDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Scheduled(fixedDelayString = "${bluesky.outbox.poll-millis:250}")
    public void poll() {
        dispatcher.dispatchPending(workerId, 100);
    }
}
