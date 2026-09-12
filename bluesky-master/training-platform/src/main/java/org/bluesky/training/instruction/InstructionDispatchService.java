package org.bluesky.training.instruction;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.bluesky.training.adapter.EngineInstanceService;
import org.bluesky.training.common.TransactionalOutboxService;
import org.bluesky.training.common.V2DomainException;
import org.bluesky.training.persistence.AircraftV2Mapper;
import org.bluesky.training.persistence.InstructionV2Mapper;
import org.bluesky.training.persistence.OutboxEventRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * P09：指令调度执行链路（评审 P0-6；详细设计 6.3.1/6.3.2/6.3.5/6.3.6、10.1）。
 * BEGIN_DISPATCH 事务内：占 dispatch_slot + 建 PENDING_APPLY 引导目标 +
 * 写 INSTRUCTION_APPLY Outbox（幂等键=instructionId，routeTo 引擎实例）；
 * Adapter 确认/拒绝/5 秒超时分别驱动状态机并释放占位（P0-7 REPLACE 语义在
 * 确认事务内生效：APPLIED 前不动旧目标）；终态统一收口后继与父项聚合（P0-9/P0-12）。
 */
@Service
public class InstructionDispatchService {

    static final long DISPATCH_ACK_TIMEOUT_MILLIS = 5_000L;

    private static final Logger log = LoggerFactory.getLogger(InstructionDispatchService.class);

    private final InstructionV2Mapper mapper;
    private final AircraftV2Mapper aircraftMapper;
    private final InstructionCatalog catalog;
    private final InstructionStateMachine stateMachine = new InstructionStateMachine();
    private final DispatchSlotService slotService;
    private final GuidanceTargetService guidanceService;
    private final TransactionalOutboxService outboxService;
    private final EngineInstanceService engineInstanceService;
    private final org.bluesky.training.report.ReportWriteService reportWriteService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    @org.springframework.beans.factory.annotation.Autowired private BusinessFieldInstructionService businessFields;
    @org.springframework.beans.factory.annotation.Autowired private org.bluesky.training.aircraft.FlightPlanService flightPlans;

    public InstructionDispatchService(InstructionV2Mapper mapper,
                                      AircraftV2Mapper aircraftMapper,
                                      InstructionCatalog catalog,
                                      DispatchSlotService slotService,
                                      GuidanceTargetService guidanceService,
                                      TransactionalOutboxService outboxService,
                                      EngineInstanceService engineInstanceService,
                                      org.bluesky.training.report.ReportWriteService reportWriteService) {
        this.mapper = mapper;
        this.aircraftMapper = aircraftMapper;
        this.catalog = catalog;
        this.slotService = slotService;
        this.guidanceService = guidanceService;
        this.outboxService = outboxService;
        this.engineInstanceService = engineInstanceService;
        this.reportWriteService = reportWriteService;
    }

    /**
     * 与 BEGIN_DISPATCH 同事务执行（详细设计 6.3.5）：占每个受影响通道的在途占位，
     * 建引导目标，写 Adapter Outbox。幂等键=instructionId：Outbox 重试天然复用。
     */
    @Transactional
    public void begin(String instructionId) {
        Map<String, Object> instruction = requireInstruction(instructionId);
        String aircraftId = String.valueOf(instruction.get("exercise_aircraft_id"));
        String type = String.valueOf(instruction.get("instruction_type"));
        for (Map<String,Object> child : mapper.childrenOf(instructionId)) {
            String childId=String.valueOf(child.get("childId"));
            if ("VALIDATED".equals(String.valueOf(requireInstruction(childId).get("status"))))
                transition(childId,"VALIDATED","BEGIN_DISPATCH");
        }
        for (String channel : catalog.affectedChannelsFor(type)) {
            slotService.claim(aircraftId, channel + ":" + aircraftId, instructionId);
            guidanceService.createPending(instructionId, aircraftId, channel,
                    String.valueOf(instruction.get("parsed_payload")));
        }
        // 技术确认截止落库（评审 E12）：重启后看门狗仍可扫描超时
        mapper.markDispatchDeadline(instructionId, mapper.databaseNow().plusNanos(
                DISPATCH_ACK_TIMEOUT_MILLIS * 1_000_000));
        enqueueInstructionApply(instruction, aircraftId, type);
    }

    /** Adapter 确认 INSTRUCTION_APPLIED：确认事务内激活引导并实施 REPLACE 语义（评审 P0-7）。 */
    @Transactional
    public void onAdapterApplied(String instructionId) {
        Map<String, Object> instruction = mapper.findById(instructionId);
        if (instruction == null
                || !"DISPATCHING".equals(String.valueOf(instruction.get("status")))) {
            // 迟到响应不得覆盖已确认状态（详细设计 10.1.7；超时/取消可能先行终结）
            log.debug("忽略迟到 INSTRUCTION_APPLIED: {} status={}", instructionId,
                    instruction == null ? "GONE" : instruction.get("status"));
            return;
        }
        String aircraftId = String.valueOf(instruction.get("exercise_aircraft_id"));
        String type = String.valueOf(instruction.get("instruction_type"));
        transition(instructionId, "DISPATCHING", "CONFIRM_APPLIED");
        try {
            flightPlans.applyInstructionVersion(aircraftId,type,
                    objectMapper.readValue(String.valueOf(instruction.get("parsed_payload")),Map.class));
        } catch(java.io.IOException invalid) { throw new IllegalStateException(invalid); }

        boolean replace = "REPLACE".equals(String.valueOf(instruction.get("scheduling")));
        // 复合子项与父项共享冲突键：清队/替代不得误伤自己的子项（评审 P0-12）
        java.util.Set<String> ownChildIds = new java.util.HashSet<>();
        for (Map<String, Object> child : mapper.childrenOf(instructionId)) {
            ownChildIds.add(String.valueOf(child.get("childId")));
        }
        for (String channel : catalog.affectedChannelsFor(type)) {
            String conflictKey = channel + ":" + aircraftId;
            if (replace) {
                clearWaiting(aircraftId, conflictKey, instructionId, ownChildIds);
                // APPLIED 之后才动旧目标（详细设计 6.3.2）：旧 EXECUTING → REPLACED，
                // 活动 guidance → SUPERSEDED，等待队列 → CANCELLED
                for (Map<String, Object> previous
                        : mapper.findExecutingByConflictKey(aircraftId, conflictKey, instructionId)) {
                    if (!ownChildIds.contains(String.valueOf(previous.get("id")))) {
                        String oldId=String.valueOf(previous.get("id"));
                        transition(oldId, "EXECUTING", "REPLACE");
                        finalizeTerminal(oldId,"REPLACED");
                    }
                }
                mapper.supersedeActiveGuidance(aircraftId, channel);
            }
            guidanceService.activate(
                    pendingTargetId(instructionId, aircraftId, channel), aircraftId, channel);
            slotService.release(aircraftId, conflictKey, instructionId);
        }
        propagateToCompositeChildren(instructionId, "EXECUTING");
        publishStatus(instructionId);
    }

    /** Adapter 业务拒绝（INSTRUCTION_REJECTED）：FAILED + 释放占位 + 引导失败 + 后继取消。 */
    @Transactional
    public void onAdapterRejected(String instructionId, String code, String message) {
        Map<String, Object> instruction = mapper.findById(instructionId);
        if (instruction == null
                || !"DISPATCHING".equals(String.valueOf(instruction.get("status")))) {
            log.debug("忽略迟到 INSTRUCTION_REJECTED: {}", instructionId);
            return;
        }
        String aircraftId = String.valueOf(instruction.get("exercise_aircraft_id"));
        String type = String.valueOf(instruction.get("instruction_type"));
        mapper.terminateWithReason(instructionId, "DISPATCHING", "FAILED", code, message);
        for (String channel : catalog.affectedChannelsFor(type)) {
            String conflictKey = channel + ":" + aircraftId;
            failPendingGuidance(instructionId, aircraftId, channel);
            slotService.release(aircraftId, conflictKey, instructionId);
        }
        finalizeTerminal(instructionId, "FAILED");
    }

    /** 5 秒技术确认窗口耗尽（评审 P0-6）：ADAPTER_ACK_TIMEOUT 终结 TIMED_OUT。 */
    @Transactional
    public void timeout(String instructionId) {
        Map<String, Object> instruction = mapper.findById(instructionId);
        if (instruction == null
                || !"DISPATCHING".equals(String.valueOf(instruction.get("status")))) {
            return;
        }
        String aircraftId = String.valueOf(instruction.get("exercise_aircraft_id"));
        String type = String.valueOf(instruction.get("instruction_type"));
        // An acknowledgement may have been lost after application; stop this command by id.
        requestCancel(instructionId);
        mapper.terminateWithReason(instructionId, "DISPATCHING", "TIMED_OUT",
                "ADAPTER_ACK_TIMEOUT",
                "技术确认窗口 " + DISPATCH_ACK_TIMEOUT_MILLIS + "ms 内未收到 Adapter 响应");
        for (String channel : catalog.affectedChannelsFor(type)) {
            String conflictKey = channel + ":" + aircraftId;
            failPendingGuidance(instructionId, aircraftId, channel);
            slotService.release(aircraftId, conflictKey, instructionId);
        }
        finalizeTerminal(instructionId, "TIMED_OUT");
    }

    /**
     * 迁移桥拒绝（如 Protocol 1.0 不支持的指令类型）：终结 FAILED 并释放占位，
     * 防止同冲突键永久 CHANNEL_DISPATCH_IN_PROGRESS（验收级联根因）。
     */
    @Transactional
    public void handleBridgeRejected(String instructionId, String reason) {
        Map<String, Object> instruction = mapper.findById(instructionId);
        if (instruction == null
                || !"DISPATCHING".equals(String.valueOf(instruction.get("status")))) {
            return;
        }
        String aircraftId = String.valueOf(instruction.get("exercise_aircraft_id"));
        String type = String.valueOf(instruction.get("instruction_type"));
        mapper.terminateWithReason(instructionId, "DISPATCHING", "FAILED",
                "ADAPTER_REJECTED", "迁移桥无法执行该指令: " + reason);
        for (String channel : catalog.affectedChannelsFor(type)) {
            String conflictKey = channel + ":" + aircraftId;
            failPendingGuidance(instructionId, aircraftId, channel);
            slotService.release(aircraftId, conflictKey, instructionId);
        }
        finalizeTerminal(instructionId, "FAILED");
    }

    /**
     * BLOCKED 解除链路（评审 P0-9；详细设计 5.4/6.2）：释放阻塞原因后
     * 若全部清空则 UNBLOCK 进派发；训练恢复批量清 TRAINING_PAUSED 后逐条续派。
     */
    @Transactional
    public void releaseAndDispatch(String instructionId, String reason) {
        mapper.releaseBlocker(instructionId, reason);
        Map<String, Object> instruction = mapper.findById(instructionId);
        if (instruction == null || !"BLOCKED".equals(String.valueOf(instruction.get("status")))) {
            return;
        }
        if (!mapper.activeBlockers(instructionId).isEmpty()) {
            return; // 仍有阻塞原因：只有全部删除后才进入 DISPATCHING（详细设计 5.4）
        }
        transition(instructionId, "BLOCKED", "UNBLOCK");
        String type=String.valueOf(instruction.get("instruction_type"));
        if ("BUSINESS_FIELD".equals(catalog.channelOf(type)) && !Arrays.asList("P_LEVEL","P_TIME").contains(type)) {
            transition(instructionId,"DISPATCHING","CONFIRM_APPLIED");
            try {
                businessFields.apply(instructionId,String.valueOf(instruction.get("exercise_aircraft_id")),
                        String.valueOf(instruction.get("exercise_group_id")),type,
                        objectMapper.readValue(String.valueOf(instruction.get("parsed_payload")),Map.class));
            } catch (java.io.IOException invalid) { throw new IllegalStateException(invalid); }
            transition(instructionId,"EXECUTING","COMPLETE");
            finalizeTerminal(instructionId,"COMPLETED");
        } else begin(instructionId);
    }

    /** 训练 RESUME：批量清除该组 TRAINING_PAUSED 并对已清空阻塞的指令续派（评审 P0-9）。 */
    @Transactional
    public void releaseTrainingPausedForGroup(String groupId) {
        for (String instructionId : mapper.findBlockedIdsByReasonInGroup(groupId,
                "TRAINING_PAUSED")) {
            releaseAndDispatch(instructionId, "TRAINING_PAUSED");
        }
    }

    /**
     * 终态统一收口（详细设计 §5.5 命令报告恰好一条）：后继释放/取消
     * （E18：已派发后继同样处理）+ 复合父项聚合（P0-12）+ 终态命令报告。
     */
    @Transactional
    public void finalizeTerminal(String instructionId, String finalState) {
        outboxService.terminateInstructionApply(instructionId);
        releaseSlotsOf(instructionId);
        if (!"COMPLETED".equals(finalState)) {
            for (Map<String,Object> target:mapper.guidanceOf(instructionId)) {
                String state=String.valueOf(target.get("state"));
                if (Arrays.asList("ACTIVE","PENDING_APPLY").contains(state))
                    mapper.transitionGuidance(String.valueOf(target.get("id")),state,"SUPERSEDED");
            }
            for (Map<String,Object> child:mapper.childrenOf(instructionId)) {
                String id=String.valueOf(child.get("childId")); Map<String,Object> row=mapper.findById(id);
                if (row!=null && !InstructionStateMachine.isTerminal(String.valueOf(row.get("status")))) {
                    mapper.terminateWithReason(id,String.valueOf(row.get("status")),"CANCELLED","PARENT_TERMINATED",finalState);
                    finalizeTerminal(id,"CANCELLED");
                }
            }
        }
        releaseOrCancelSuccessors(instructionId, finalState);
        recomputeCompositeParentOf(instructionId);
        reportWriteService.writeCommandTerminalReport(instructionId, finalState);
        publishStatus(instructionId);
    }

    /** 前置终态处理：COMPLETED 释放后继（并续派）；未完成终态取消全部活动后继（详细设计 6.2）。 */
    @Transactional
    public void releaseOrCancelSuccessors(String predecessorId, String predecessorFinalState) {
        for (Map<String, Object> candidate : mapper.listActiveSuccessorsOf(predecessorId)) {
            String successorId = String.valueOf(candidate.get("id"));
            String status = String.valueOf(candidate.get("status"));
            if ("COMPLETED".equals(predecessorFinalState)) {
                releaseAndDispatch(successorId, "PREDECESSOR_ACTIVE");
            } else {
                mapper.terminateWithReason(successorId, status, "CANCELLED",
                        "PREDECESSOR_NOT_COMPLETED",
                        "前置指令未正常完成: " + predecessorFinalState);
                releaseSlotsOf(successorId);
                finalizeTerminal(successorId,"CANCELLED");
            }
        }
    }

    /** 复合父项聚合（评审 P0-12；详细设计 5.4）：子项终态变化驱动父状态机。 */
    @Transactional
    public void recomputeCompositeParentOf(String childInstructionId) {
        Map<String, Object> child = mapper.findById(childInstructionId);
        if (child == null || !InstructionStateMachine.isTerminal(
                String.valueOf(child.get("status")))) {
            return;
        }
        String parentInstructionId = mapper.findParentIdOf(childInstructionId);
        if (parentInstructionId == null) {
            return;
        }
        Map<String, Object> parent = mapper.findById(parentInstructionId);
        if (parent == null
                || !"EXECUTING".equals(String.valueOf(parent.get("status")))) {
            return;
        }
        List<Map<String, Object>> children = mapper.childrenOf(parentInstructionId);
        // childrenOf 返回 childId/channel；聚合需要子状态
        for (Map<String, Object> entry : children) {
            Map<String, Object> childRow = mapper.findById(
                    String.valueOf(entry.get("childId")));
            if (childRow != null) {
                entry.put("status", childRow.get("status"));
            }
        }
        String aggregated = CompositeInstructionService.aggregateParentState(children);
        if ("COMPLETED".equals(aggregated)) {
            transition(parentInstructionId, "EXECUTING", "COMPLETE");
            finalizeTerminal(parentInstructionId, "COMPLETED");
        } else if ("FAILED".equals(aggregated)) {
            mapper.terminateWithReason(parentInstructionId, "EXECUTING", "FAILED",
                    "COMPOSITE_CHILD_FAILED", "必需子项失败");
            finalizeTerminal(parentInstructionId, "FAILED");
        }
    }

    // ---------------------------------------------------------------- internal

    private void enqueueInstructionApply(Map<String, Object> instruction, String aircraftId,
                                         String type) {
        Map<String, Object> aircraft = aircraftMapper.findById(aircraftId);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("instructionId", instruction.get("id"));
        payload.put("aircraftId", aircraftId);
        payload.put("callsign", aircraft == null ? null : aircraft.get("callsign"));
        payload.put("type", type);
        try {
            payload.put("parameters", objectMapper.readValue(
                    String.valueOf(instruction.get("parsed_payload")), Map.class));
        } catch (JsonProcessingException invalid) {
            throw new V2DomainException("INVALID_INSTRUCTION", 400,
                    "指令载荷不是合法 JSON: " + instruction.get("id"),
                    Arrays.asList("parameters"));
        }
        payload.put("affectedChannels", catalog.affectedChannelsFor(type));
        String outboxId = UUID.randomUUID().toString();
        outboxService.enqueueAdapterAction(OutboxEventRow.adapterAction(
                outboxId, String.valueOf(instruction.get("exercise_group_id")),
                "INSTRUCTION_APPLY", json(payload)).routeTo(
                engineInstanceService.currentInstanceId(
                        String.valueOf(instruction.get("exercise_group_id"))),
                "req-" + outboxId,
                // 幂等键=instructionId（详细设计 6.3.5）：重试与恢复复用同一键
                "instruction:" + instruction.get("id")));
    }

    /** 复合指令确认后子项同步进入 EXECUTING（子项不单独派发，父项单次载荷全通道）。 */
    private void propagateToCompositeChildren(String instructionId, String target) {
        for (Map<String, Object> child : mapper.childrenOf(instructionId)) {
            String childId = String.valueOf(child.get("childId"));
            Map<String, Object> row = mapper.findById(childId);
            if (row != null && "DISPATCHING".equals(String.valueOf(row.get("status")))) {
                transition(childId, "DISPATCHING", "CONFIRM_APPLIED");
            }
        }
    }

    private void clearWaiting(String aircraftId, String conflictKey, String excludingId,
                              java.util.Set<String> ownChildIds) {
        for (Map<String, Object> waiting : mapper.findWaitingByConflictKey(aircraftId,
                conflictKey)) {
            String id = String.valueOf(waiting.get("id"));
            if (id.equals(excludingId) || ownChildIds.contains(id)) {
                continue;
            }
            mapper.terminateWithReason(id, String.valueOf(waiting.get("status")), "CANCELLED",
                    "QUEUE_CLEARED_BY_REPLACE", "同冲突键队列被实时替代清除");
            finalizeTerminal(id,"CANCELLED");
        }
    }

    private void failPendingGuidance(String instructionId, String aircraftId, String channel) {
        for (Map<String, Object> target : mapper.guidanceOf(instructionId)) {
            if (channel.equals(String.valueOf(target.get("channel")))
                    && "PENDING_APPLY".equals(String.valueOf(target.get("state")))) {
                mapper.transitionGuidance(String.valueOf(target.get("id")),
                        "PENDING_APPLY", "FAILED");
            }
        }
    }

    private String pendingTargetId(String instructionId, String aircraftId, String channel) {
        for (Map<String, Object> target : mapper.guidanceOf(instructionId)) {
            if (channel.equals(String.valueOf(target.get("channel")))
                    && "PENDING_APPLY".equals(String.valueOf(target.get("state")))) {
                return String.valueOf(target.get("id"));
            }
        }
        throw new V2DomainException("GUIDANCE_FAILED", 502,
                "缺少 PENDING_APPLY 引导目标: " + instructionId + "/" + channel);
    }

    private void releaseSlotsOf(String instructionId) {
        Map<String, Object> instruction = mapper.findById(instructionId);
        if (instruction == null) {
            return;
        }
        String aircraftId = String.valueOf(instruction.get("exercise_aircraft_id"));
        String type = String.valueOf(instruction.get("instruction_type"));
        for (String channel : catalog.affectedChannelsFor(type)) {
            mapper.releaseSlot(aircraftId, channel + ":" + aircraftId, instructionId);
        }
    }

    @Transactional
    public void requestCancel(String instructionId) {
        Map<String,Object> instruction=requireInstruction(instructionId);
        String aid=String.valueOf(instruction.get("exercise_aircraft_id"));
        Map<String,Object> aircraft=aircraftMapper.lockById(aid);
        String group=String.valueOf(instruction.get("exercise_group_id"));
        String instance=engineInstanceService.currentInstanceId(group), key="instruction-cancel:"+instructionId;
        outboxService.terminateInstructionApply(instructionId);
        if (outboxService.hasAdapterAction(instance,key)) return;
        Map<String,Object> payload=new LinkedHashMap<>();
        payload.put("instructionId",instructionId); payload.put("aircraftId",aid);
        payload.put("callsign",aircraft.get("callsign"));
        payload.put("affectedChannels",catalog.affectedChannelsFor(String.valueOf(instruction.get("instruction_type"))));
        String outboxId=UUID.randomUUID().toString();
        outboxService.enqueueAdapterAction(OutboxEventRow.adapterAction(outboxId,group,"INSTRUCTION_CANCEL",json(payload)).routeTo(instance,"req-"+outboxId,key));
    }

    @Transactional
    public void cancelForDeletedAircraft(String aircraftId) {
        List<String> ids=mapper.activeIdsByAircraft(aircraftId);
        mapper.terminateActiveByAircraft(aircraftId);
        for(String id:ids)finalizeTerminal(id,"CANCELLED");
        mapper.supersedeAllActiveGuidance(aircraftId);
    }

    @Transactional
    public void onAdapterCancelled(String instructionId) {
        Map<String,Object> row=requireInstruction(instructionId);String from=String.valueOf(row.get("status"));
        if (!InstructionStateMachine.isTerminal(from)) {
            mapper.terminateWithReason(instructionId,from,"CANCELLED",null,null);
            finalizeTerminal(instructionId,"CANCELLED");
        }
    }

    private void transition(String instructionId, String from, String event) {
        String target = stateMachine.transition(from, event);
        int changed = mapper.transitionStatus(instructionId, from, target);
        if (changed != 1) {
            throw new V2DomainException("REVISION_CONFLICT", 409,
                    "指令状态已被并发修改: " + instructionId);
        }
    }

    private Map<String, Object> requireInstruction(String instructionId) {
        Map<String, Object> instruction = instructionId == null ? null
                : mapper.findById(instructionId);
        if (instruction == null) {
            throw new V2DomainException("TERMINAL_NOT_FOUND", 404,
                    "指令不存在: " + instructionId);
        }
        return instruction;
    }

    private String json(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("INSTRUCTION_APPLY 载荷序列化失败", e);
        }
    }

    private void publishStatus(String id) {
        Map<String,Object> row=mapper.findById(id);
        if(row!=null)outboxService.enqueueBusinessEvent(OutboxEventRow.businessEvent(UUID.randomUUID().toString(),String.valueOf(row.get("exercise_group_id")),"instruction.status.changed",json(row)));
    }
}
