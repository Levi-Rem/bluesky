package org.bluesky.training.assignment;

import org.bluesky.training.common.TerminalAccessPolicy;
import org.bluesky.training.common.V2DomainException;
import org.bluesky.training.persistence.BusinessEventMapper;
import org.bluesky.training.event.BusinessEventService;
import org.bluesky.training.persistence.AircraftV2Mapper;
import org.bluesky.training.persistence.HandoverMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * P08：按频率直接原子移交（详细设计 2.2 §5.3）。
 * 固定锁顺序：航空器行 → 当前分配行；并发移交只有第一个事务成功，
 * 其余返回 409 AIRCRAFT_ASSIGNMENT_CHANGED；唯一 current key 兜底。
 */
@Service
public class HandoverService {

    private final HandoverMapper handoverMapper;
    private final AircraftV2Mapper aircraftMapper;
    private final BusinessEventMapper businessEventMapper;
    private final BusinessEventService businessEventService;
    private final TerminalAccessPolicy terminalAccessPolicy;

    public HandoverService(HandoverMapper handoverMapper,
                           AircraftV2Mapper aircraftMapper,
                           BusinessEventMapper businessEventMapper,
                           BusinessEventService businessEventService,
                           TerminalAccessPolicy terminalAccessPolicy) {
        this.handoverMapper = handoverMapper;
        this.aircraftMapper = aircraftMapper;
        this.businessEventMapper = businessEventMapper;
        this.businessEventService = businessEventService;
        this.terminalAccessPolicy = terminalAccessPolicy;
    }

    @Transactional
    public Map<String, Object> handoverByFrequency(
            org.bluesky.training.common.CallerContext caller, String aircraftId,
            long aircraftRevision, double targetFrequencyMhz) {
        terminalAccessPolicy.requireTerminalWrite(caller);
        if (!Double.isFinite(targetFrequencyMhz)
                || targetFrequencyMhz <= 0 || targetFrequencyMhz >= 1000) {
            throw new V2DomainException("INVALID_INSTRUCTION", 400,
                    "目标频率必须是 0–1000 之间的有限 MHz 数值",
                    Arrays.asList("targetFrequencyMhz"));
        }

        // 锁顺序第一步：航空器行
        Map<String, Object> aircraft = handoverMapper.lockAircraftForUpdate(aircraftId);
        if (aircraft == null) {
            throw new V2DomainException("TERMINAL_NOT_FOUND", 404, "航空器不存在: " + aircraftId);
        }
        String groupId = String.valueOf(aircraft.get("exercise_group_id"));
        terminalAccessPolicy.requireSameGroup(caller, groupId);

        String lifecycle = String.valueOf(aircraft.get("lifecycle"));
        if (!"ACTIVE".equals(lifecycle)) {
            throw new V2DomainException("TRAINING_STATE_INVALID", 409,
                    "只有活动航空器允许移交，当前: " + lifecycle,
                    Arrays.asList("lifecycle"));
        }
        Map<String, Object> group = aircraftMapper.findGroupStateAndTime(groupId);
        terminalAccessPolicy.requireGroupStateAllows(String.valueOf(group.get("state")),
                Arrays.asList("RUNNING", "PAUSED"));

        // revision 必须先于源席校验，确保并发败方稳定返回分配冲突而不是 403。
        long actualRevision = ((Number) aircraft.get("revision")).longValue();
        if (actualRevision != aircraftRevision) {
            throw new V2DomainException("AIRCRAFT_ASSIGNMENT_CHANGED", 409,
                    "航空器分配已变化，当前修订号 " + actualRevision,
                    Arrays.asList("aircraftRevision"));
        }

        // 锁顺序第二步：当前分配行；先校验源终端仍持有控制权，再拒绝自移交
        Map<String, Object> current = handoverMapper.lockCurrentAssignmentForUpdate(aircraftId);
        if (current == null
                || !caller.terminalId().equals(String.valueOf(current.get("terminal_id")))) {
            throw new V2DomainException("AIRCRAFT_NOT_ASSIGNED", 403,
                    "源终端已不再持有该航空器", Arrays.asList("terminalId"));
        }
        Map<String, Object> target = resolveTargetTerminal(
                groupId, BigDecimal.valueOf(targetFrequencyMhz).setScale(3, RoundingMode.HALF_UP));
        String targetTerminalId = String.valueOf(target.get("id"));
        if (targetTerminalId.equals(caller.terminalId())) {
            throw new V2DomainException("INVALID_INSTRUCTION", 400,
                    "目标终端不能是源终端", Arrays.asList("targetFrequencyMhz"));
        }

        // 并发抢占点：条件 revision 更新（WHERE revision = expected）先行
        int changed = handoverMapper.incrementRevisionAndReassign(aircraftId, actualRevision,
                targetTerminalId);
        if (changed != 1) {
            throw new V2DomainException("AIRCRAFT_ASSIGNMENT_CHANGED", 409,
                    "航空器分配已被并发修改", Arrays.asList("aircraftRevision"));
        }
        handoverMapper.insertHandover(UUID.randomUUID().toString(), aircraftId,
                caller.terminalId(), targetTerminalId,
                BigDecimal.valueOf(targetFrequencyMhz).setScale(3, RoundingMode.HALF_UP),
                actualRevision);
        // 只变业务责任与 revision：不取消指令、不触碰引导目标/队列
        aircraftMapper.endAssignment(String.valueOf(current.get("id")));
        try {
            aircraftMapper.insertAssignment(UUID.randomUUID().toString(), aircraftId,
                    targetTerminalId);
        } catch (org.springframework.dao.DuplicateKeyException raced) {
            // 唯一 current key 兜底：并发方已建立新分配
            throw new V2DomainException("AIRCRAFT_ASSIGNMENT_CHANGED", 409,
                    "航空器分配已被并发修改", Arrays.asList("aircraftRevision"));
        }

        Map<String, Object> response = buildResponse(aircraftId, groupId, caller.terminalId(),
                targetTerminalId, targetFrequencyMhz, actualRevision + 1);
        publishHandoverCompleted(groupId, response);
        return response;
    }

    /** 同事务发布 aircraft.handover.completed 并扇出组内全部启用终端（详细设计 5.3.5）。 */
    private void publishHandoverCompleted(String groupId, Map<String, Object> response) {
        businessEventService.appendFanOut(groupId, "aircraft.handover.completed",
                jsonOf("aircraftId", response.get("aircraftId"),
                        "sourceTerminalId", response.get("sourceTerminalId"),
                        "targetTerminalId", response.get("targetTerminalId"),
                        "aircraftRevision", response.get("aircraftRevision")),
                businessEventMapper.listEnabledTerminalIds(groupId));
    }

    private Map<String, Object> resolveTargetTerminal(String groupId, BigDecimal frequency) {
        Map<String, Object> target = handoverMapper.findTerminalByFrequency(groupId, frequency);
        if (target == null
                || ((Number) target.get("enabled")).intValue() != 1) {
            throw new V2DomainException("TERMINAL_NOT_FOUND", 404,
                    "同组不存在启用且频率为 " + frequency.toPlainString() + " 的终端",
                    Arrays.asList("targetFrequencyMhz"));
        }
        return target;
    }

    private Map<String, Object> buildResponse(String aircraftId, String groupId,
                                              String sourceTerminalId, String targetTerminalId,
                                              double targetFrequencyMhz, long newRevision) {
        Double simulationTime = businessEventMapper.findGroupSimulationTime(groupId);
        List<Map<String, Object>> summaries = handoverMapper.activeInstructionSummaries(aircraftId);
        List<Map<String, Object>> active = new ArrayList<>();
        List<Map<String, Object>> waiting = new ArrayList<>();
        for (Map<String, Object> summary : summaries) {
            // v2 状态语义：RECEIVED/VALIDATED/BLOCKED 属等待（被阻塞或排队），其余在活动执行
            String status = String.valueOf(summary.get("status"));
            if ("RECEIVED".equals(status) || "VALIDATED".equals(status)
                    || "BLOCKED".equals(status)) {
                waiting.add(summary);
            } else {
                active.add(summary);
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("aircraftId", aircraftId);
        body.put("sourceTerminalId", sourceTerminalId);
        body.put("targetTerminalId", targetTerminalId);
        body.put("targetFrequencyMhz", targetFrequencyMhz);
        body.put("aircraftRevision", newRevision);
        body.put("activeInstructions", active);
        body.put("waitingInstructions", waiting);
        body.put("completedSimulationTimeSeconds",
                simulationTime == null ? 0.0 : simulationTime);
        return body;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> assignments(
            org.bluesky.training.common.CallerContext caller, String groupId) {
        terminalAccessPolicy.requireSameGroup(caller, groupId);
        return handoverMapper.listCurrentAssignments(groupId);
    }

    private static String jsonOf(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(map);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("事件载荷序列化失败", e);
        }
    }
}
