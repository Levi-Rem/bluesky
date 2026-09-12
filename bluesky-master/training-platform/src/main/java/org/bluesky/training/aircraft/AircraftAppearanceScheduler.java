package org.bluesky.training.aircraft;

import org.bluesky.training.common.TransactionalOutboxService;
import org.bluesky.training.common.V2DomainException;
import org.bluesky.training.persistence.AircraftV2Mapper;
import org.bluesky.training.persistence.EngineInstanceMapper;
import org.bluesky.training.persistence.OutboxEventRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** P07：出现调度（详细设计 5.2.4：STARTING/RUNNING 且到期才条件更新认领 + AIRCRAFT_APPLY）。 */
@Service
public class AircraftAppearanceScheduler {

    private static final int MAX_CLAIMS_PER_SCAN = 1000;

    private final AircraftV2Mapper aircraftMapper;
    private final TransactionalOutboxService outboxService;
    private final EngineInstanceMapper engineInstanceMapper;
    private final AircraftAdapterPayloadFactory payloadFactory;

    public AircraftAppearanceScheduler(AircraftV2Mapper aircraftMapper,
                                       TransactionalOutboxService outboxService,
                                       EngineInstanceMapper engineInstanceMapper,
                                       AircraftAdapterPayloadFactory payloadFactory) {
        this.aircraftMapper = aircraftMapper;
        this.outboxService = outboxService;
        this.engineInstanceMapper = engineInstanceMapper;
        this.payloadFactory = payloadFactory;
    }

    /** 组 RUNNING 且目标时刻到达：条件更新认领并写 AIRCRAFT_APPLY（幂等键 = 航空器 ID）。 */
    @Transactional
    public Map<String, Object> scanDuePlans(String groupId) {
        Map<String, Object> group = aircraftMapper.findGroupStateAndTime(groupId);
        if (group == null) {
            throw new V2DomainException("TERMINAL_NOT_FOUND", 404, "训练组不存在: " + groupId);
        }
        String state = String.valueOf(group.get("state"));
        if (!"RUNNING".equals(state) && !"STARTING".equals(state)) {
            // 暂停/过渡态：出现倒计时冻结，不调度
            return result(groupId, state, 0, null);
        }
        Object rawSimulationTime = group.get("simulation_time_seconds");
        if (!(rawSimulationTime instanceof Number)) {
            throw new V2DomainException("TRAINING_STATE_INVALID", 409,
                    "训练组仿真时钟不是有效数值: " + groupId);
        }
        BigDecimal simulationTime = BigDecimal.valueOf(
                ((Number) rawSimulationTime).doubleValue());
        String dueId = aircraftMapper.findDuePlannedAircraft(groupId, simulationTime);
        if (dueId == null) {
            return result(groupId, state, 0, null);
        }
        return requestAppearance(dueId);
    }

    /** 供启动编排一次装载全部已到期计划；有上限以防损坏数据造成无限循环。 */
    @Transactional
    public int requestAllDuePlans(String groupId) {
        int claimed = 0;
        while (claimed < MAX_CLAIMS_PER_SCAN) {
            Map<String, Object> result = scanDuePlans(groupId);
            if (((Number) result.get("claimed")).intValue() != 1) {
                return claimed;
            }
            claimed++;
        }
        throw new IllegalStateException("单次出现调度超过上限: " + groupId);
    }

    public boolean hasCreateRequested(String groupId) {
        return aircraftMapper.countCreateRequested(groupId) > 0;
    }

    public boolean isGroupStarting(String groupId) {
        Map<String, Object> group = aircraftMapper.findGroupStateAndTime(groupId);
        return group != null && "STARTING".equals(String.valueOf(group.get("state")));
    }

    public java.util.List<String> runningGroupIds() {
        return aircraftMapper.listRunningGroupIds();
    }

    @Transactional
    public Map<String, Object> requestAppearance(String aircraftId) {
        Map<String, Object> planned = aircraftMapper.findById(aircraftId);
        if (planned == null) {
            throw new V2DomainException("TERMINAL_NOT_FOUND", 404,
                    "航空器不存在: " + aircraftId);
        }
        String plannedGroupId = String.valueOf(planned.get("exercise_group_id"));
        String engineInstanceId = engineInstanceMapper.findGroupCurrentInstanceId(plannedGroupId);
        if (engineInstanceId == null) {
            // 先检查引擎再认领，避免 ENGINE_UNAVAILABLE 时把 PLANNED 永久卡成
            // CREATE_REQUESTED；调用方可在引擎恢复后安全重扫。
            throw new V2DomainException("ENGINE_UNAVAILABLE", 503,
                    "训练组没有当前引擎实例: " + plannedGroupId);
        }
        int claimed = aircraftMapper.claimDuePlan(aircraftId);
        if (claimed != 1) {
            // 另一个 worker 已认领：本次无副作用
            return result(null, null, 0, aircraftId);
        }
        Map<String, Object> aircraft = aircraftMapper.findById(aircraftId);
        String groupId = String.valueOf(aircraft.get("exercise_group_id"));
        // 引擎可能在认领后刚好被停止；再次校验保证不会写入无法投递的路由。
        engineInstanceId = engineInstanceMapper.findGroupCurrentInstanceId(groupId);
        if (engineInstanceId == null) {
            throw new V2DomainException("ENGINE_UNAVAILABLE", 503,
                    "训练组没有当前引擎实例: " + groupId);
        }
        outboxService.enqueueAdapterAction(OutboxEventRow.adapterAction(
                UUID.randomUUID().toString(), groupId, "AIRCRAFT_APPLY",
                json(payloadFactory.build(aircraftId)))
                .routeTo(engineInstanceId, "aircraft-apply-" + aircraftId, aircraftId));
        return result(groupId, "RUNNING", 1, aircraftId);
    }

    private static Map<String, Object> result(String groupId, String state, int claimed,
                                              String aircraftId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("groupId", groupId);
        body.put("groupState", state);
        body.put("claimed", claimed);
        body.put("aircraftId", aircraftId);
        return body;
    }

    private static String json(Object value) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Outbox 载荷序列化失败", e);
        }
    }
}
