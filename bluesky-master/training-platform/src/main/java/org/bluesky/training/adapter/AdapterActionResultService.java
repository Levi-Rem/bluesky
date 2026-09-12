package org.bluesky.training.adapter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.bluesky.training.aircraft.AircraftLifecycleService;
import org.bluesky.training.exercise.ExerciseGroupService;
import org.bluesky.training.exercise.ExerciseStartCoordinator;
import org.bluesky.training.common.OutboxClaimService;
import org.bluesky.training.common.V2DomainException;
import org.bluesky.training.persistence.OutboxEventRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Objects;

/** 把 Adapter 技术确认推进为可重入领域步骤；拒绝不会被误记为发送成功。 */
@Service
public class AdapterActionResultService {

    private final EngineInstanceService engineService;
    private final ExerciseStartCoordinator startCoordinator;
    private final ExerciseGroupService groupService;
    private final OutboxClaimService claimService;
    private final AircraftLifecycleService aircraftLifecycleService;
    private final org.bluesky.training.aircraft.AircraftDeletionSaga deletionSaga;
    private final org.bluesky.training.exercise.SimulationClockService simulationClockService;
    private final org.bluesky.training.instruction.InstructionDispatchService
            instructionDispatchService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AdapterActionResultService(EngineInstanceService engineService,
                                      ExerciseStartCoordinator startCoordinator,
                                      ExerciseGroupService groupService,
                                      OutboxClaimService claimService,
                                      AircraftLifecycleService aircraftLifecycleService,
                                      org.bluesky.training.aircraft.AircraftDeletionSaga deletionSaga,
                                      org.bluesky.training.exercise.SimulationClockService
                                              simulationClockService,
                                      org.bluesky.training.instruction.InstructionDispatchService
                                              instructionDispatchService) {
        this.engineService = engineService;
        this.startCoordinator = startCoordinator;
        this.groupService = groupService;
        this.claimService = claimService;
        this.aircraftLifecycleService = aircraftLifecycleService;
        this.deletionSaga = deletionSaga;
        this.simulationClockService = simulationClockService;
        this.instructionDispatchService = instructionDispatchService;
    }

    @Transactional
    public void handleAndConfirm(OutboxEventRow row, Map<String, Object> response) {
        validateResponseType(row, response);
        handleLifecycle(row, response);
        handleAircraftApply(row, response);
        handleAircraftDelete(row, response);
        handleInstructionApply(row, response);
        if ("INSTRUCTION_CANCEL".equals(row.getEventType())) {
            Map<?,?> payload=(Map<?,?>)response.get("payload");
            if (!Boolean.TRUE.equals(payload.get("accepted"))) throw new AdapterProtocolException("ADAPTER_CANCEL_REJECTED","取消尚未确认");
            try { instructionDispatchService.onAdapterCancelled(String.valueOf(objectMapper.readValue(row.getPayload(),Map.class).get("instructionId"))); }
            catch (java.io.IOException invalid) { throw new IllegalStateException(invalid); }
        }
        claimService.confirm(row.getId());
    }

    /**
     * INSTRUCTION_APPLY 确认回路（评审 P0-6；详细设计 6.3）：
     * applied → CONFIRM_APPLIED（REPLACE 语义在确认事务内生效，P0-7）；
     * rejected → FAILED；迟到响应由 dispatch 服务按状态判定忽略（10.1.7）。
     */
    private void handleInstructionApply(OutboxEventRow row, Map<String, Object> response) {
        if (!"INSTRUCTION_APPLY".equals(row.getEventType())) {
            return;
        }
        Map<?, ?> payload = response.get("payload") instanceof Map
                ? (Map<?, ?>) response.get("payload") : java.util.Collections.emptyMap();
        String instructionId;
        try {
            Map<?, ?> requestPayload = objectMapper.readValue(row.getPayload(), Map.class);
            instructionId = String.valueOf(requestPayload.get("instructionId"));
        } catch (java.io.IOException invalid) {
            throw new AdapterProtocolException("ADAPTER_ACTION_INVALID",
                    "INSTRUCTION_APPLY 载荷不是合法 JSON: " + row.getId());
        }
        if (Boolean.TRUE.equals(payload.get("accepted"))) {
            instructionDispatchService.onAdapterApplied(instructionId);
        } else {
            String code = payload.get("code") == null
                    ? "ADAPTER_REJECTED" : String.valueOf(payload.get("code"));
            String message = payload.get("message") == null
                    ? "Adapter 拒绝执行指令" : String.valueOf(payload.get("message"));
            instructionDispatchService.onAdapterRejected(instructionId, code, message);
        }
    }

    /**
     * AIRCRAFT_DELETE 确认回路（评审 P0-4；详细设计 7.9）：
     * accepted → confirmDeleted（取消指令/清引导/结束分配/写 DELETED）；
     * rejected → DELETE_FAILED；AIRCRAFT_EXISTS_GET 对账结果驱动重启恢复。
     */
    private void handleAircraftDelete(OutboxEventRow row, Map<String, Object> response) {
        String eventType = row.getEventType();
        if (!"AIRCRAFT_DELETE".equals(eventType) && !"AIRCRAFT_EXISTS_GET".equals(eventType)) {
            return;
        }
        Map<?, ?> payload = response.get("payload") instanceof Map
                ? (Map<?, ?>) response.get("payload") : java.util.Collections.emptyMap();
        String aircraftId = actionAircraftId(row);
        if ("AIRCRAFT_DELETE".equals(eventType)) {
            if (Boolean.TRUE.equals(payload.get("accepted"))) {
                deletionSaga.confirmDeleted(aircraftId);
            } else {
                String code = payload.get("code") == null
                        ? "ADAPTER_REJECTED" : String.valueOf(payload.get("code"));
                String message = payload.get("message") == null
                        ? "Adapter 拒绝删除航空器" : String.valueOf(payload.get("message"));
                deletionSaga.markDeleteFailed(aircraftId, code, message);
            }
            return;
        }
        // AIRCRAFT_EXISTS_GET：重启对账（丢 ACK 的 DELETE_REQUESTED 行）
        if (!Boolean.TRUE.equals(payload.get("accepted")) || !(payload.get("exists") instanceof Boolean)) {
            throw new AdapterProtocolException("ADAPTER_QUERY_FAILED", "实体存在性查询未得到有效确认");
        }
        boolean exists = Boolean.TRUE.equals(payload.get("exists"));
        deletionSaga.reconcileUnknownResult(aircraftId, exists);
    }

    private void handleAircraftApply(OutboxEventRow row, Map<String, Object> response) {
        if (!"AIRCRAFT_APPLY".equals(row.getEventType())) {
            return;
        }
        Map<?, ?> responsePayload = response.get("payload") instanceof Map
                ? (Map<?, ?>) response.get("payload") : java.util.Collections.emptyMap();
        String aircraftId = actionAircraftId(row);
        if (Boolean.TRUE.equals(responsePayload.get("accepted"))) {
            Object time = response.get("simulationTimeSeconds");
            double actualAppearanceTime = time instanceof Number
                    ? ((Number) time).doubleValue() : 0.0;
            aircraftLifecycleService.confirmActive(aircraftId, actualAppearanceTime);
            startCoordinator.continueAfterAircraftApplied(
                    row.getExerciseGroupId(), row.getEngineInstanceId());
            return;
        }
        String code = responsePayload.get("code") == null
                ? "ADAPTER_REJECTED" : String.valueOf(responsePayload.get("code"));
        String message = responsePayload.get("message") == null
                ? "Adapter 拒绝创建航空器" : String.valueOf(responsePayload.get("message"));
        aircraftLifecycleService.markCreateFailed(aircraftId, code, message);
        if (row.getEngineInstanceId() != null
                && startCoordinator != null) {
            // 启动期任一到期计划被拒绝都不能继续 START；回退 READY 后由用户修复并重试。
            try {
                groupService.compensateFailedStart(row.getExerciseGroupId(),
                        code + ": " + message);
                engineService.markStopped(row.getEngineInstanceId());
            } catch (V2DomainException notStarting) {
                // RUNNING 期间的延迟出现失败只影响航空器自身，不改变训练组状态。
                if (!"TRAINING_STATE_INVALID".equals(notStarting.code())) {
                    throw notStarting;
                }
            }
        }
    }

    private String actionAircraftId(OutboxEventRow row) {
        try {
            Map<?, ?> payload = objectMapper.readValue(row.getPayload(), Map.class);
            Object id = payload.get("aircraftId");
            if (id == null || String.valueOf(id).trim().isEmpty()) {
                throw new AdapterProtocolException("ADAPTER_ACTION_INVALID",
                        "AIRCRAFT_APPLY 缺少 aircraftId: " + row.getId());
            }
            return String.valueOf(id);
        } catch (JsonProcessingException invalid) {
            throw new AdapterProtocolException("ADAPTER_ACTION_INVALID",
                    "AIRCRAFT_APPLY 载荷不是合法 JSON: " + row.getId());
        }
    }

    private void validateResponseType(OutboxEventRow row, Map<String, Object> response) {
        String expectedMessageType = expectedResponseType(row.getEventType(), response);
        if (!expectedMessageType.equals(response.get("messageType"))) {
            throw new AdapterProtocolException("UNEXPECTED_RESPONSE",
                    "动作 " + row.getEventType() + " 期望 " + expectedMessageType
                            + " 实际 " + response.get("messageType"));
        }
    }

    private void handleLifecycle(OutboxEventRow row, Map<String, Object> response) {
        if (!isLifecycleAction(row.getEventType())) {
            return;
        }
        String engineId = row.getEngineInstanceId();
        engineService.assertCurrentInstance(row.getExerciseGroupId(), engineId);
        Map<?, ?> payload = response.get("payload") instanceof Map
                ? (Map<?, ?>) response.get("payload") : java.util.Collections.emptyMap();
        if (!Boolean.TRUE.equals(payload.get("accepted"))) {
            String reason = String.valueOf(payload.get("code")) + ": " + payload.get("message");
            if ("HELLO".equals(row.getEventType())
                    || "REFERENCE_SNAPSHOT_LOAD".equals(row.getEventType())
                    || "START".equals(row.getEventType())) {
                engineService.markDegraded(engineId);
                groupService.compensateFailedStart(row.getExerciseGroupId(), reason);
                // 失败补偿必须尽力停止已创建实例，否则留下孤儿引擎（评审 C4）
                startCoordinator.enqueueStop(row.getExerciseGroupId(), engineId);
            } else if ("PAUSE".equals(row.getEventType())
                    || "RESUME".equals(row.getEventType())) {
                groupService.enterRecovering(row.getExerciseGroupId(), reason);
            } else if ("STOP".equals(row.getEventType())) {
                // ENDING 只有确认停止或完成超时对账后才能进入 ENDED；拒绝时保留原动作重试。
                throw new AdapterProtocolException("ADAPTER_LIFECYCLE_REJECTED", reason);
            }
            return;
        }
        switch (row.getEventType()) {
            case "HELLO":
                engineService.markConnected(engineId);
                startCoordinator.enqueueReferenceLoad(row.getExerciseGroupId(), engineId);
                break;
            case "REFERENCE_SNAPSHOT_LOAD":
                String actual = String.valueOf(payload.get("manifestChecksum"));
                String expected = startCoordinator.expectedManifestChecksum(row.getExerciseGroupId());
                if (!Objects.equals(expected, actual)) {
                    engineService.markDegraded(engineId);
                    groupService.compensateFailedStart(row.getExerciseGroupId(),
                            "REFERENCE_CHECKSUM_MISMATCH");
                    startCoordinator.enqueueStop(row.getExerciseGroupId(), engineId);
                    break;
                }
                engineService.updateSnapshotChecksum(engineId, actual);
                startCoordinator.enqueueStart(row.getExerciseGroupId(), engineId);
                break;
            case "START":
                groupService.onAdapterLifecycleResult(row.getExerciseGroupId(), "STARTED");
                break;
            case "PAUSE":
                if(row.getPayload().contains("\"recovery\":true"))groupService.completeRecovery(row.getExerciseGroupId());
                else groupService.onAdapterLifecycleResult(row.getExerciseGroupId(), "PAUSED");
                // 采用 Adapter 返回的实际暂停时刻（详细设计 4.2.7；评审 C3/A5）：
                // 缺字段时退回响应信封的 simulationTimeSeconds
                simulationClockService.freezeAtPause(row.getExerciseGroupId(),
                        actualPauseSeconds(payload, response));
                break;
            case "RESUME":
                groupService.onAdapterLifecycleResult(row.getExerciseGroupId(), "RESUMED");
                // 恢复后批量清除 TRAINING_PAUSED 阻塞并续派（评审 P0-9；详细设计 4.2.6）
                instructionDispatchService.releaseTrainingPausedForGroup(row.getExerciseGroupId());
                break;
            case "STOP":
                engineService.markStopped(engineId);
                try {
                    groupService.onAdapterLifecycleResult(row.getExerciseGroupId(), "STOPPED");
                } catch (V2DomainException alreadyCompensated) {
                    // 失败补偿路径补发的 STOP（评审 C4）：组已离开 ENDING 时，
                    // 该 STOP 是尽力停止的幂等收据，完成 Outbox 即可
                    if (!"TRAINING_STATE_INVALID".equals(alreadyCompensated.code())) {
                        throw alreadyCompensated;
                    }
                }
                break;
            default:
                break;
        }
    }

    private static double actualPauseSeconds(Map<?, ?> payload, Map<String, Object> response) {
        Object actual = payload.get("actualPauseSimulationTimeSeconds");
        if (actual instanceof Number) {
            return ((Number) actual).doubleValue();
        }
        Object envelopeTime = response.get("simulationTimeSeconds");
        return envelopeTime instanceof Number ? ((Number) envelopeTime).doubleValue() : 0.0;
    }

    private static boolean isLifecycleAction(String eventType) {
        return java.util.Arrays.asList("HELLO", "REFERENCE_SNAPSHOT_LOAD", "START",
                "PAUSE", "RESUME", "STOP").contains(eventType);
    }

    private static String expectedResponseType(String action, Map<String, Object> response) {
        switch (action) {
            case "HELLO": return "HELLO_ACK";
            case "REFERENCE_SNAPSHOT_LOAD": return "REFERENCE_SNAPSHOT_ACK";
            case "SPECIAL_PROFILE_LOAD": return "SPECIAL_PROFILE_ACK";
            case "START": return "STARTED";
            case "PAUSE": return "PAUSED";
            case "RESUME": return "RESUMED";
            case "STOP": return "STOPPED";
            case "RESET_DEMO_ONLY": return "RESET_DEMO_ONLY";
            case "AIRCRAFT_APPLY": return "AIRCRAFT_APPLIED";
            case "AIRCRAFT_DELETE": return "AIRCRAFT_DELETED";
            case "AIRCRAFT_EXISTS_GET": return "AIRCRAFT_EXISTS_RESULT";
            case "INSTRUCTION_APPLY":
                return responseAccepted(response) ? "INSTRUCTION_APPLIED" : "INSTRUCTION_REJECTED";
            case "INSTRUCTION_CANCEL": return "INSTRUCTION_CANCELLED";
            case "INSTRUCTION_STATUS_GET": return "INSTRUCTION_STATUS_RESULT";
            case "RECOVERY_CHECKPOINT_LOAD": return "RECOVERY_CHECKPOINT_ACK";
            case "STATE_SNAPSHOT_GET": return "STATE_SNAPSHOT_CHUNK";
            default:
                throw new AdapterProtocolException("ADAPTER_ACTION_INVALID",
                        "未定义请求—响应映射: " + action);
        }
    }

    private static boolean responseAccepted(Map<String, Object> response) {
        Object payload = response.get("payload");
        return payload instanceof Map && Boolean.TRUE.equals(((Map<?, ?>) payload).get("accepted"));
    }
}
