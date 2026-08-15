package org.bluesky.training.event;

import org.bluesky.training.workstation.WorkstationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class EventStreamService {
    private static final long SSE_TIMEOUT_MILLIS = 0L;
    private static final String DEFAULT_GROUP_ID = "GROUP-DEFAULT";

    private final WorkstationService workstationService;
    private final CopyOnWriteArrayList<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    public EventStreamService(WorkstationService workstationService) {
        this.workstationService = workstationService;
    }

    public SseEmitter connect(String exerciseGroupId) {
        if (!DEFAULT_GROUP_ID.equals(exerciseGroupId)) {
            throw new IllegalArgumentException("首版只支持默认训练组");
        }

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MILLIS);
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(error -> emitters.remove(emitter));
        try {
            emitter.send(SseEmitter.event()
                    .name("snapshot")
                    .data(workstationService.bootstrap()));
        } catch (IOException exception) {
            emitters.remove(emitter);
            emitter.completeWithError(exception);
        }
        return emitter;
    }

    public void publish(String eventName, Object data) {
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name(eventName).data(data));
            } catch (IOException | IllegalStateException exception) {
                emitters.remove(emitter);
                emitter.complete();
            }
        }
    }

    public void publishAfterCommit(String eventName, Object data) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()
                || !TransactionSynchronizationManager.isActualTransactionActive()) {
            publish(eventName, data);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        publish(eventName, data);
                    }
                });
    }
}
