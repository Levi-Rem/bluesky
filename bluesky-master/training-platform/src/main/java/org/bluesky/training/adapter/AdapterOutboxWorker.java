package org.bluesky.training.adapter;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** ADAPTER_ACTION Outbox 的生产轮询接线。 */
@Component
@ConditionalOnProperty(name = "bluesky.outbox.workers-enabled",
        havingValue = "true", matchIfMissing = true)
public class AdapterOutboxWorker {

    private final AdapterOutboxDispatcher dispatcher;
    private final AdapterActionSender sender;
    private final String workerId = "adapter-action-" + UUID.randomUUID();

    public AdapterOutboxWorker(AdapterOutboxDispatcher dispatcher, AdapterActionSender sender) {
        this.dispatcher = dispatcher;
        this.sender = sender;
    }

    @Scheduled(fixedDelayString = "${bluesky.outbox.poll-millis:250}")
    public void poll() {
        dispatcher.dispatchPending(workerId, sender, 100);
    }
}
