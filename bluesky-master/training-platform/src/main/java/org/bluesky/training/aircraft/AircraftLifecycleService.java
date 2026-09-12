package org.bluesky.training.aircraft;

import org.bluesky.training.common.TransactionalOutboxService;
import org.bluesky.training.common.V2DomainException;
import org.bluesky.training.persistence.AircraftV2Mapper;
import org.bluesky.training.persistence.EngineInstanceMapper;
import org.bluesky.training.persistence.OutboxEventMapper;
import org.bluesky.training.persistence.OutboxEventRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** P07：创建状态机与动态状态投影（详细设计 5.2.4/5.2.5；重试复用同一幂等键）。 */
@Service
public class AircraftLifecycleService {

    private final AircraftV2Mapper aircraftMapper;
    private final EngineInstanceMapper engineInstanceMapper;
    private final OutboxEventMapper outboxEventMapper;
    private final TransactionalOutboxService outboxService;
    private final AircraftAdapterPayloadFactory payloadFactory;

    public AircraftLifecycleService(AircraftV2Mapper aircraftMapper,
                                    EngineInstanceMapper engineInstanceMapper,
                                    OutboxEventMapper outboxEventMapper,
                                    TransactionalOutboxService outboxService,
                                    AircraftAdapterPayloadFactory payloadFactory) {
        this.aircraftMapper = aircraftMapper;
        this.engineInstanceMapper = engineInstanceMapper;
        this.outboxEventMapper = outboxEventMapper;
        this.outboxService = outboxService;
        this.payloadFactory = payloadFactory;
    }

    /** Adapter 确认实体存在且状态匹配后写 ACTIVE 与实际出现时刻（详细设计 5.2.4）。 */
    @Transactional
    public Map<String, Object> confirmActive(String aircraftId, double actualAppearanceTimeSeconds) {
        int changed = aircraftMapper.transitionLifecycle(aircraftId, "CREATE_REQUESTED",
                "ACTIVE", BigDecimal.valueOf(actualAppearanceTimeSeconds));
        if (changed != 1) {
            throw new V2DomainException("TRAINING_STATE_INVALID", 409,
                    "只有 CREATE_REQUESTED 可以确认 ACTIVE: " + aircraftId,
                    Arrays.asList("lifecycle"));
        }
        Map<String, Object> aircraft = aircraftMapper.findById(aircraftId);
        publishLifecycle(aircraft, "ACTIVE");
        return aircraft;
    }

    @Transactional
    public Map<String, Object> markCreateFailed(String aircraftId, String code, String message) {
        int changed = aircraftMapper.markCreateFailed(aircraftId, "CREATE_REQUESTED", "CREATE_FAILED",
                code, message);
        if (changed != 1) {
            throw new V2DomainException("TRAINING_STATE_INVALID", 409,
                    "只有 CREATE_REQUESTED 可以标记创建失败: " + aircraftId,
                    Arrays.asList("lifecycle"));
        }
        Map<String, Object> aircraft = aircraftMapper.findById(aircraftId);
        publishLifecycle(aircraft, "CREATE_FAILED");
        return aircraft;
    }

    /** 重试创建：复用航空器 ID 作为幂等键，BlueSky 侧不得重复创建实体。 */
    @Transactional
    public Map<String, Object> retryCreate(String aircraftId) {
        int changed = aircraftMapper.transitionLifecycle(aircraftId, "CREATE_FAILED",
                "CREATE_REQUESTED", null);
        if (changed != 1) {
            throw new V2DomainException("TRAINING_STATE_INVALID", 409,
                    "只有 CREATE_FAILED 可以重试创建: " + aircraftId, Arrays.asList("lifecycle"));
        }
        Map<String, Object> aircraft = aircraftMapper.findById(aircraftId);
        String groupId = String.valueOf(aircraft.get("exercise_group_id"));
        String engineInstanceId = engineInstanceMapper.findGroupCurrentInstanceId(groupId);
        if (engineInstanceId == null) {
            throw new V2DomainException("ENGINE_UNAVAILABLE", 503,
                    "训练组没有当前引擎实例: " + groupId);
        }
        if (outboxEventMapper.retryAdapterAction(engineInstanceId, aircraftId) != 1) {
            String outboxId = UUID.randomUUID().toString();
            outboxService.enqueueAdapterAction(OutboxEventRow.adapterAction(
                    outboxId, groupId, "AIRCRAFT_APPLY",
                    json(payloadFactory.build(aircraftId)))
                    .routeTo(engineInstanceId, "aircraft-apply-" + aircraftId, aircraftId));
        }
        publishLifecycle(aircraft, "CREATE_REQUESTED");
        return aircraft;
    }

    /** Adapter 动态帧投影：仅 ACTIVE 航空器接受（迟到/越权帧返回 false）。 */
    @Transactional
    public boolean applyStateFrame(String aircraftId, double latitude, double longitude,
                                   double trueHeadingDeg, double altitudeFtMsl,
                                   double indicatedAirspeedKt, double verticalRateFpm) {
        return aircraftMapper.applyStateFrame(aircraftId, latitude, longitude, trueHeadingDeg,
                altitudeFtMsl, indicatedAirspeedKt, verticalRateFpm) == 1;
    }

    private void publishLifecycle(Map<String, Object> aircraft, String lifecycle) {
        String groupId = String.valueOf(aircraft.get("exercise_group_id"));
        outboxService.enqueueBusinessEvent(OutboxEventRow.businessEvent(
                UUID.randomUUID().toString(), groupId, "aircraft.lifecycle.changed",
                jsonOf("aircraftId", aircraft.get("id"), "lifecycle", lifecycle,
                        "revision", aircraft.get("revision"))));
    }

    private static String jsonOf(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(map);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Outbox 载荷序列化失败", e);
        }
    }

    private static String json(Object value) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Outbox 载荷序列化失败", e);
        }
    }

}
