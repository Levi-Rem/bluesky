package org.bluesky.training.aircraft;

import org.bluesky.training.adapter.EngineInstanceService;
import org.bluesky.training.common.TransactionalOutboxService;
import org.bluesky.training.common.V2DomainException;
import org.bluesky.training.persistence.AircraftV2Mapper;
import org.bluesky.training.persistence.InstructionV2Mapper;
import org.bluesky.training.persistence.OutboxEventRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * P15：可恢复删除 Saga（详细设计 2.2 §7.9）。
 * DELETE_REQUESTED 冻结新指令并写 Adapter Outbox；Adapter 确认或对账实体不存在后
 * 写 DELETED 并结束责任分配；失败进入 DELETE_FAILED 可重试；未出现航空器本地直达。
 * 评审 D1：确认事务内取消指令、清除活动引导目标；
 * 评审 D2：生命周期迁移与责任席校验单语句原子完成；
 * 评审 D3：组状态校验（RECOVERING/RECOVERY_FAILED → 503）；
 * 评审 D4：Outbox routeTo 引擎实例，幂等键航空器维度（重试复用）。
 */
@Service
public class AircraftDeletionSaga {

    private final AircraftV2Mapper aircraftMapper;
    private final InstructionV2Mapper instructionMapper;
    private final TransactionalOutboxService outboxService;
    private final EngineInstanceService engineInstanceService;
    @org.springframework.beans.factory.annotation.Autowired
    private org.bluesky.training.instruction.InstructionDispatchService dispatch;

    public AircraftDeletionSaga(AircraftV2Mapper aircraftMapper,
                                InstructionV2Mapper instructionMapper,
                                TransactionalOutboxService outboxService,
                                EngineInstanceService engineInstanceService) {
        this.aircraftMapper = aircraftMapper;
        this.instructionMapper = instructionMapper;
        this.outboxService = outboxService;
        this.engineInstanceService = engineInstanceService;
    }

    /** 发起删除：仅当前责任席；未出现航空器本地直达 DELETED（详细设计 5.2.9）。 */
    @Transactional
    public Map<String, Object> requestDelete(
            org.bluesky.training.common.CallerContext caller, String aircraftId,
            String deleteRequestId) {
        Map<String, Object> aircraft = requireAircraft(aircraftId);
        String groupId = String.valueOf(aircraft.get("exercise_group_id"));
        requireGroupDeletable(groupId);
        Map<String, Object> current = aircraftMapper.findCurrentAssignment(aircraftId);
        if (current == null
                || !caller.terminalId().equals(String.valueOf(current.get("terminal_id")))) {
            throw new V2DomainException("AIRCRAFT_NOT_ASSIGNED", 403,
                    "只有当前责任席可以删除", Arrays.asList("terminalId"));
        }
        String lifecycle = String.valueOf(aircraft.get("lifecycle"));
        if("DELETE_FAILED".equals(lifecycle))return retry(aircraftId);

        if ("PLANNED".equals(lifecycle) || "CREATE_FAILED".equals(lifecycle)) {
            // 无 BlueSky 实体：同事务直达 DELETED，不写 Adapter Outbox
            requireTransitionSucceeded(aircraftMapper.markDeleted(aircraftId, lifecycle));
            endAssignmentOf(aircraftId);
            cancelInstructionsOf(aircraftId);
            return envelope(aircraftId, "DELETED", false);
        }
        if (!"ACTIVE".equals(lifecycle) && !"DELETE_FAILED".equals(lifecycle)) {
            throw new V2DomainException("TRAINING_STATE_INVALID", 409,
                    "当前生命周期不可删除: " + lifecycle, Arrays.asList("lifecycle"));
        }

        String from = "DELETE_FAILED".equals(lifecycle) ? "DELETE_FAILED" : "ACTIVE";
        // D2：席别校验内嵌于生命周期 CAS——移交并发下旧席的删除在单语句层面失败
        int changed = aircraftMapper.transitionLifecycleAsResponsibleTerminal(
                aircraftId, from, "DELETE_REQUESTED", caller.terminalId());
        if (changed != 1) {
            throw new V2DomainException("REVISION_CONFLICT", 409,
                    "删除请求与并发修改冲突（或已非当前责任席）",
                    Arrays.asList("aircraftRevision"));
        }
        enqueueAircraftDelete(groupId, aircraftId);
        return envelope(aircraftId, "DELETE_REQUESTED", true);
    }

    /** Adapter 确认删除/实体不存在：取消指令、清引导、结束分配、写 DELETED。 */
    @Transactional
    public Map<String, Object> confirmDeleted(String aircraftId) {
        // DELETE_FAILED（对账路径）也允许补记 DELETED
        int changed = aircraftMapper.transitionLifecycle(aircraftId, "DELETE_REQUESTED",
                "DELETED", null);
        if (changed == 0) {
            changed = aircraftMapper.transitionLifecycle(aircraftId, "DELETE_FAILED",
                    "DELETED", null);
        }
        if (changed == 1) {
            aircraftMapper.markDeleted(aircraftId, "DELETED"); // 置 active_callsign_key NULL 等
            endAssignmentOf(aircraftId);
            // D1：确认事务内取消指令、清除活动引导，不留残留活动态
            cancelInstructionsOf(aircraftId);
        }
        return envelope(aircraftId, "DELETED", false);
    }

    /** Adapter 拒绝/5 秒无法确认：保留航空器与责任席位，仅允许重试或编排方取消。 */
    @Transactional
    public Map<String, Object> markDeleteFailed(String aircraftId, String code, String message) {
        int changed = aircraftMapper.markCreateFailed(aircraftId, "DELETE_REQUESTED",
                "DELETE_FAILED", code, message);
        requireTransitionSucceeded(changed);
        return envelope(aircraftId, "DELETE_FAILED", false);
    }

    /** 责任席重试：复用航空器维度的删除幂等语义（评审 D4：键与首删一致）。 */
    @Transactional
    public Map<String, Object> retry(String aircraftId) {
        Map<String, Object> aircraft = requireAircraft(aircraftId);
        int changed = aircraftMapper.transitionLifecycle(aircraftId, "DELETE_FAILED",
                "DELETE_REQUESTED", null);
        if (changed != 1) {
            throw new V2DomainException("TRAINING_STATE_INVALID", 409,
                    "只有 DELETE_FAILED 可以重试删除", Arrays.asList("lifecycle"));
        }
        String groupId=String.valueOf(aircraft.get("exercise_group_id"));
        if (!outboxService.retryAdapterAction(engineInstanceService.currentInstanceId(groupId),
                deleteIdempotencyKey(aircraftId))) {
            throw new V2DomainException("TRAINING_STATE_INVALID",409,"删除动作仍在处理，稍后重试");
        }
        return envelope(aircraftId, "DELETE_REQUESTED", true);
    }

    /** 仅训练编排方取消失败删除；必须先确认 Adapter 实体仍存在（详细设计 7.9）。 */
    @Transactional
    public Map<String, Object> cancelFailedDelete(String aircraftId, boolean adapterEntityExists) {
        if (!adapterEntityExists) {
            // Adapter 已不存在：只能补记 DELETED，不能取消
            return confirmDeleted(aircraftId);
        }
        int changed = aircraftMapper.transitionLifecycle(aircraftId, "DELETE_FAILED",
                "ACTIVE", null);
        if (changed != 1) {
            throw new V2DomainException("TRAINING_STATE_INVALID", 409,
                    "只有 DELETE_FAILED 可以取消删除", Arrays.asList("lifecycle"));
        }
        return envelope(aircraftId, "ACTIVE", false);
    }

    /** 重启对账：丢 ACK 时先查实体存在性，再决定补记完成或重试（详细设计 7.9）。 */
    @Transactional
    public Map<String, Object> reconcileUnknownResult(String aircraftId,
                                                      boolean adapterEntityExists) {
        Map<String, Object> aircraft = requireAircraft(aircraftId);
        if (!"DELETE_REQUESTED".equals(String.valueOf(aircraft.get("lifecycle")))) {
            return envelope(aircraftId, String.valueOf(aircraft.get("lifecycle")), false);
        }
        return adapterEntityExists
                ? retryAfterReconcile(aircraftId)
                : confirmDeleted(aircraftId);
    }

    private Map<String, Object> retryAfterReconcile(String aircraftId) {
        int moved = aircraftMapper.transitionLifecycle(aircraftId, "DELETE_REQUESTED",
                "DELETE_FAILED", null);
        if (moved != 1) {
            // 并发确认/超时已推进状态：以当前状态返回
            return envelope(aircraftId, lifecycleOf(aircraftId), false);
        }
        aircraftMapper.markCreateFailed(aircraftId, "DELETE_FAILED", "DELETE_FAILED",
                "DELETE_RECONCILIATION_REQUIRED", "对账发现实体仍存在，转为可重试");
        return envelope(aircraftId, "DELETE_FAILED", false);
    }

    private String lifecycleOf(String aircraftId) {
        Map<String, Object> aircraft = aircraftMapper.findById(aircraftId);
        return aircraft == null ? "UNKNOWN" : String.valueOf(aircraft.get("lifecycle"));
    }

    /**
     * AIRCRAFT_DELETE Outbox（评审 D4）：routeTo 引擎实例 + 请求 ID +
     * 幂等键（= 航空器 ID + 删除请求 ID 维度，重试复用同一键）。
     */
    private void enqueueAircraftDelete(String groupId, String aircraftId) {
        String requestId = "req-" + UUID.randomUUID();
        String outboxId = UUID.randomUUID().toString();
        outboxService.enqueueAdapterAction(OutboxEventRow.adapterAction(
                outboxId, groupId, "AIRCRAFT_DELETE",
                jsonOf("aircraftId", aircraftId, "callsign", requireAircraft(aircraftId).get("callsign")))
                .routeTo(engineInstanceService.currentInstanceId(groupId), requestId,
                        deleteIdempotencyKey(aircraftId)));
    }

    private static String deleteIdempotencyKey(String aircraftId) {
        // 航空器维度的删除唯一：成功后不可再删，重试天然复用（详细设计 7.9）
        return "aircraft-delete:" + aircraftId;
    }

    private void cancelInstructionsOf(String aircraftId) {
        dispatch.cancelForDeletedAircraft(aircraftId);
    }

    private void requireGroupDeletable(String groupId) {
        Map<String, Object> group = aircraftMapper.findGroupStateAndTime(groupId);
        String state = group == null ? null : String.valueOf(group.get("state"));
        if ("RECOVERING".equals(state) || "RECOVERY_FAILED".equals(state)) {
            throw new V2DomainException("ENGINE_RECOVERING", 503,
                    "引擎恢复中，暂不能删除航空器", Arrays.asList("groupState"));
        }
        if (!Arrays.asList("RUNNING", "PAUSED").contains(state)) {
            throw new V2DomainException("TRAINING_STATE_INVALID", 409,
                    "当前训练状态不可删除航空器: " + state, Arrays.asList("groupState"));
        }
    }

    private static void requireTransitionSucceeded(int changed) {
        if (changed != 1) {
            throw new V2DomainException("REVISION_CONFLICT", 409,
                    "删除状态迁移与并发修改冲突", Arrays.asList("lifecycle"));
        }
    }

    private void endAssignmentOf(String aircraftId) {
        Map<String, Object> current = aircraftMapper.findCurrentAssignment(aircraftId);
        if (current != null) {
            aircraftMapper.endAssignment(String.valueOf(current.get("id")));
        }
    }

    private Map<String, Object> requireAircraft(String aircraftId) {
        Map<String, Object> aircraft = aircraftId == null ? null
                : aircraftMapper.findById(aircraftId);
        if (aircraft == null) {
            throw new V2DomainException("TERMINAL_NOT_FOUND", 404, "航空器不存在: " + aircraftId);
        }
        return aircraft;
    }

    private static Map<String, Object> envelope(String aircraftId, String state,
                                                boolean adapterPending) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("operationId", UUID.randomUUID().toString());
        body.put("resourceId", aircraftId);
        body.put("state", state);
        body.put("adapterPending", adapterPending);
        return body;
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
}
