package org.bluesky.training.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.bluesky.training.common.OutboxClaimService;
import org.bluesky.training.persistence.OutboxEventMapper;
import org.bluesky.training.persistence.OutboxEventRow;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * P04：发送 Adapter Outbox 并确认（详细设计 3.3.8 / 10.1.7）。
 * 只派发 ADAPTER_ACTION 行；成功 SENT、失败退避重排、耗尽 FAILED。
 */
@Service
public class AdapterOutboxDispatcher {

    /** 传输抽象：生产实现包装 AdapterControlClient，测试用仿冒。 */
    public interface Sender {
        Map<String, Object> send(Map<String, Object> action);

        /** 数据库确认成功后释放发送方为重试保留的瞬时资源。 */
        default void confirmed(String outboxEventId) {
            // 测试发送方和无状态发送方无需处理。
        }
    }

    private static final int MAX_BACKOFF_SECONDS = 600;

    private final OutboxClaimService claimService;
    private final OutboxEventMapper outboxMapper;
    private final AdapterActionResultService resultService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AdapterOutboxDispatcher(OutboxClaimService claimService,
                                   OutboxEventMapper outboxMapper,
                                   AdapterActionResultService resultService) {
        this.claimService = claimService;
        this.outboxMapper = outboxMapper;
        this.resultService = resultService;
    }

    public int dispatchPending(String workerId, Sender sender, int limit) {
        int dispatched = 0;
        for (String id : claimService.claimBatchForKind(
                workerId, limit, OutboxEventRow.KIND_ADAPTER_ACTION)) {
            OutboxEventRow row = outboxMapper.findById(id);
            if (row == null || !"PENDING".equals(row.getStatus())) {
                continue;
            }
            try {
                Map<String, Object> response = sender.send(parsePayload(row));
                Object payload = response == null ? null : response.get("payload");
                Object accepted = payload instanceof Map ? ((Map<?, ?>) payload).get("accepted") : null;
                if (!(accepted instanceof Boolean)) {
                    throw new AdapterProtocolException("ADAPTER_ACK_INVALID",
                            "Adapter 响应缺少 payload.accepted: " + id);
                }
                // accepted=false 是确定性业务拒绝，也已完成技术确认；不得继续重试同一动作。
                // 领域推进与 Outbox CONFIRMED 必须原子提交，避免崩溃后重复推进 Saga。
                resultService.handleAndConfirm(row, response);
                sender.confirmed(id);
                dispatched++;
            } catch (Exception failure) {
                int nextAttempt = row.getAttemptCount() + 1;
                claimService.recordFailure(id, nextAttempt, row.getMaxAttempts(), backoff(nextAttempt));
            }
        }
        return dispatched;
    }

    /** 收到关联确认后终结 Outbox 行。 */
    public void handleResponse(String outboxEventId) {
        claimService.confirm(outboxEventId);
    }

    private Map<String, Object> parsePayload(OutboxEventRow row) {
        try {
            Object parsed = objectMapper.readValue(row.getPayload(), Object.class);
            if (parsed instanceof Map) {
                Map<String, Object> action = new LinkedHashMap<>((Map<String, Object>) parsed);
                action.put("outboxEventId", row.getId());
                action.put("eventType", row.getEventType());
                action.put("exerciseGroupId", row.getExerciseGroupId());
                putIfNotNull(action, "engineInstanceId", row.getEngineInstanceId());
                putIfNotNull(action, "requestId", row.getRequestId());
                putIfNotNull(action, "idempotencyKey", row.getIdempotencyKey());
                action.put("payloadChecksum", row.getPayloadChecksum());
                return action;
            }
            Map<String, Object> action = new LinkedHashMap<>();
            action.put("outboxEventId", row.getId());
            action.put("eventType", row.getEventType());
            action.put("rawPayload", row.getPayload());
            return action;
        } catch (IOException e) {
            throw new UncheckedIOException("Outbox 载荷解析失败: " + row.getId(), e);
        }
    }

    private static void putIfNotNull(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    private static Timestamp backoff(int attempt) {
        long seconds = Math.min(30L * Math.max(1, attempt), MAX_BACKOFF_SECONDS);
        return Timestamp.from(Instant.now().plusSeconds(seconds));
    }
}
