package org.bluesky.training.adapter;

import org.bluesky.training.persistence.EngineInstanceMapper;
import org.bluesky.training.persistence.EngineInstanceRow;
import org.springframework.stereotype.Service;

import javax.annotation.PreDestroy;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 生产 Adapter Outbox 传输：按当前实例复用 DEALER 客户端并保持外层重试信封稳定。 */
@Service
public class AdapterActionSender implements AdapterOutboxDispatcher.Sender {

    private final EngineInstanceMapper engineMapper;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private EngineControlSequenceService sequences;
    private final Map<String, AdapterControlClient> clients = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> pendingEnvelopes = new ConcurrentHashMap<>();

    public AdapterActionSender(EngineInstanceMapper engineMapper) {
        this.engineMapper = engineMapper;
    }

    @Override
    public Map<String, Object> send(Map<String, Object> action) {
        String groupId = required(action, "exerciseGroupId");
        String outboxId = required(action, "outboxEventId");
        String currentInstanceId = engineMapper.findGroupCurrentInstanceId(groupId);
        String routedInstanceId = action.get("engineInstanceId") == null
                ? currentInstanceId : String.valueOf(action.get("engineInstanceId"));
        if (currentInstanceId == null || !currentInstanceId.equals(routedInstanceId)) {
            throw new AdapterProtocolException("STALE_ENGINE_INSTANCE",
                    "Outbox 动作不是训练组当前实例: " + outboxId);
        }
        String instanceId = routedInstanceId;
        EngineInstanceRow instance = instanceId == null ? null : engineMapper.findById(instanceId);
        if (instance == null || "STOPPED".equals(instance.getState())) {
            throw new AdapterProtocolException("ENGINE_INSTANCE_UNAVAILABLE",
                    "训练组没有可用的当前引擎实例: " + groupId);
        }
        AdapterControlClient client = clients.computeIfAbsent(instanceId, ignored -> {
            AdapterControlClient created = new AdapterControlClient();
            created.connect(instance.getControlEndpoint());
            return created;
        });
        synchronized (client) {
        Map<String, Object> envelope = pendingEnvelopes.computeIfAbsent(outboxId, ignored -> {
            Map<String, Object> payload = new LinkedHashMap<>(action);
            payload.keySet().removeAll(java.util.Arrays.asList(
                    "outboxEventId", "eventType", "exerciseGroupId", "engineInstanceId",
                    "requestId", "idempotencyKey", "payloadChecksum"));
            Map<String, Object> request = client.createRequestEnvelope(
                    required(action, "eventType"), groupId, instanceId,
                    action.get("idempotencyKey") == null
                            ? outboxId : String.valueOf(action.get("idempotencyKey")), payload);
            request.put("requestId", action.get("requestId") == null
                    ? "req-" + outboxId : action.get("requestId"));
            if (sequences != null) request.put("sequence", sequences.next(instanceId));
            return request;
        });
        return client.send(envelope);
        }
    }

    @Override
    public void confirmed(String outboxEventId) {
        pendingEnvelopes.remove(outboxEventId);
    }

    @PreDestroy
    public void close() {
        for (AdapterControlClient client : clients.values()) {
            client.close();
        }
        clients.clear();
        pendingEnvelopes.clear();
    }

    private static String required(Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (value == null || String.valueOf(value).trim().isEmpty()) {
            throw new AdapterProtocolException("ADAPTER_ACTION_INVALID", "缺少 " + key);
        }
        return String.valueOf(value);
    }
}
