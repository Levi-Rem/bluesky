package org.bluesky.training.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.bluesky.training.common.OutboxClaimService;
import org.bluesky.training.persistence.BusinessEventMapper;
import org.bluesky.training.persistence.OutboxEventMapper;
import org.bluesky.training.persistence.OutboxEventRow;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** 把 BUSINESS_EVENT Outbox 可靠投影为业务事件与逐终端投递。 */
@Service
public class BusinessEventOutboxDispatcher {

    private final OutboxClaimService claimService;
    private final OutboxEventMapper outboxMapper;
    private final BusinessEventMapper eventMapper;
    private final BusinessEventService eventService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public BusinessEventOutboxDispatcher(OutboxClaimService claimService,
                                         OutboxEventMapper outboxMapper,
                                         BusinessEventMapper eventMapper,
                                         BusinessEventService eventService) {
        this.claimService = claimService;
        this.outboxMapper = outboxMapper;
        this.eventMapper = eventMapper;
        this.eventService = eventService;
    }

    public int dispatchPending(String workerId, int limit) {
        int dispatched = 0;
        for (String id : claimService.claimBatchForKind(
                workerId, limit, OutboxEventRow.KIND_BUSINESS_EVENT)) {
            OutboxEventRow row = outboxMapper.findById(id);
            if (row == null) {
                continue;
            }
            try {
                Map<String, Object> payload = parse(row.getPayload());
                List<String> targets = targetTerminalIds(payload, row.getExerciseGroupId());
                eventService.appendFromOutbox(row.getId(), row.getExerciseGroupId(),
                        row.getEventType(), text(payload.get("entityId")),
                        row.getPayload(), targets);
                claimService.confirm(id);
                dispatched++;
            } catch (Exception failure) {
                int nextAttempt = row.getAttemptCount() + 1;
                claimService.recordFailure(id, nextAttempt, row.getMaxAttempts(),
                        Timestamp.from(Instant.now().plusSeconds(Math.min(600L, 30L * nextAttempt))));
            }
        }
        return dispatched;
    }

    private List<String> targetTerminalIds(Map<String, Object> payload, String groupId) {
        Object requested = payload.get("targetTerminalIds");
        if (requested instanceof List) {
            List<String> terminalIds = new java.util.ArrayList<>();
            for (Object value : (List<?>) requested) {
                if (value == null || String.valueOf(value).trim().isEmpty()) {
                    throw new IllegalArgumentException("targetTerminalIds 不能包含空值");
                }
                terminalIds.add(String.valueOf(value));
            }
            return terminalIds;
        }
        List<String> enabled = eventMapper.listEnabledTerminalIds(groupId);
        return enabled == null ? Collections.emptyList() : enabled;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parse(String json) throws java.io.IOException {
        Object value = objectMapper.readValue(json, Object.class);
        return value instanceof Map ? (Map<String, Object>) value : Collections.emptyMap();
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
