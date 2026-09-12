package org.bluesky.training.event;

import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** P06：1 Hz 可覆盖动态帧（详细设计 9.5：只保留最新，不积压、不承诺逐帧续传）。 */
@Service
public class DynamicFrameService {

    private final Map<String, Map<String, Object>> latestByGroup = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> sequencesByGroup = new ConcurrentHashMap<>();

    public Map<String, Object> publishLatest(String groupId, Map<String, Object> frame) {
        long stateFrameSequence = sequencesByGroup
                .computeIfAbsent(groupId, key -> new AtomicLong()).incrementAndGet();
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventType", "aircraft.state.frame");
        envelope.put("exerciseGroupId", groupId);
        envelope.put("stateFrameSequence", stateFrameSequence);
        envelope.put("payload", frame);
        latestByGroup.put(groupId, envelope);
        return envelope;
    }

    public Map<String, Object> latestForGroup(String groupId) {
        return latestByGroup.get(groupId);
    }
}
