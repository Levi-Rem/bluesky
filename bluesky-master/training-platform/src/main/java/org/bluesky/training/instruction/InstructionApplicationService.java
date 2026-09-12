package org.bluesky.training.instruction;

import org.bluesky.training.common.OperationPolicy;
import org.bluesky.training.common.TerminalAccessPolicy;
import org.bluesky.training.common.V2DomainException;
import org.bluesky.training.persistence.AircraftV2Mapper;
import org.bluesky.training.persistence.InstructionV2Mapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * P09：指令事务入口（详细设计 2.2 §5.4/§9.4）。
 * RECEIVED → VALIDATED → BLOCKED/DISPATCHING；纯业务字段指令同事务直达 EXECUTING/COMPLETED；
 * 暂停接收并加 TRAINING_PAUSED 阻塞原因。
 */
@Service
public class InstructionApplicationService {

    private final InstructionV2Mapper mapper;
    private final AircraftV2Mapper aircraftMapper;
    private final InstructionCatalog catalog;
    private final InstructionStateMachine stateMachine;
    private final InstructionBlockerService blockerService;
    private final InstructionQueueService queueService;
    private final TerminalAccessPolicy terminalAccessPolicy;
    private final OperationPolicy operationPolicy;
    private final CommandTextParser commandTextParser;
    private final CompositeInstructionService compositeService;
    private final InstructionDispatchService dispatchService;
    private final BusinessFieldInstructionService businessFieldService;
    private final SpecialCapabilityProfileService capabilityProfileService;
    @org.springframework.beans.factory.annotation.Autowired
    private CommandReferenceValidator referenceValidator;

    public InstructionApplicationService(InstructionV2Mapper mapper,
                                         AircraftV2Mapper aircraftMapper,
                                         InstructionCatalog catalog,
                                         InstructionBlockerService blockerService,
                                         InstructionQueueService queueService,
                                         TerminalAccessPolicy terminalAccessPolicy,
                                         OperationPolicy operationPolicy,
                                         CommandTextParser commandTextParser,
                                         CompositeInstructionService compositeService,
                                         InstructionDispatchService dispatchService,
                                         BusinessFieldInstructionService businessFieldService,
                                         SpecialCapabilityProfileService capabilityProfileService) {
        this.mapper = mapper;
        this.aircraftMapper = aircraftMapper;
        this.catalog = catalog;
        this.stateMachine = new InstructionStateMachine();
        this.blockerService = blockerService;
        this.queueService = queueService;
        this.terminalAccessPolicy = terminalAccessPolicy;
        this.operationPolicy = operationPolicy;
        this.commandTextParser = commandTextParser;
        this.compositeService = compositeService;
        this.dispatchService = dispatchService;
        this.businessFieldService = businessFieldService;
        this.capabilityProfileService = capabilityProfileService;
    }

    @Transactional
    public Map<String, Object> submit(org.bluesky.training.common.CallerContext caller,
                                      String aircraftId, Map<String, Object> payload) {
        terminalAccessPolicy.requireTerminalWrite(caller);
        Map<String, Object> aircraft = aircraftMapper.lockById(aircraftId);
        if (aircraft == null) {
            throw new V2DomainException("TERMINAL_NOT_FOUND", 404, "航空器不存在: " + aircraftId);
        }
        String groupId = String.valueOf(aircraft.get("exercise_group_id"));
        terminalAccessPolicy.requireSameGroup(caller, groupId);
        requireCurrentResponsibility(caller, aircraftId);
        if (!"ACTIVE".equals(String.valueOf(aircraft.get("lifecycle")))) {
            throw new V2DomainException("TRAINING_STATE_INVALID", 409,
                    "航空器未出现，不能提交飞行指令",
                    Arrays.asList("lifecycle"));
        }

        // 幂等：同键同摘要想返回原指令，异摘要 409（详细设计 9.1；
        // 摘要覆盖 scheduling 与命令体——仅调度方式不同也是不同请求）
        String idempotencyKey = text(payload.get("idempotencyKey"));
        String idempotencyRequestDigest = idempotencyKey == null
                ? null : instructionRequestDigest(payload);
        if (idempotencyKey != null) {
            String existingDigest = mapper.findDigestByIdempotencyKey(aircraftId, idempotencyKey);
            if (existingDigest != null) {
                if (!existingDigest.equals(idempotencyRequestDigest)) {
                    throw new V2DomainException("IDEMPOTENCY_KEY_REUSED", 409,
                            "同一幂等键已用于不同请求",
                            Arrays.asList("Idempotency-Key"));
                }
                String existing = mapper.findByIdempotencyKey(aircraftId, idempotencyKey);
                if (existing != null) {
                    Map<String, Object> original = mapper.findById(existing);
                    original.put("replayed", true);
                    return original;
                }
            }
        }

        if (payload.containsKey("aircraftRevision")) {
            long expected=org.bluesky.training.common.RevisionHeaders.requireIfMatch(String.valueOf(payload.get("aircraftRevision")));
            new org.bluesky.training.common.RevisionGuard().requireExpected(expected, ((Number)aircraft.get("revision")).longValue());
        }

        // 工作台非创建类关键字（与 FRE/DEL 同类，§7.10）：CANCEL 取消该机
        // 最新活动指令，不创建新指令；返回被取消行供终端原地刷新状态。
        // 位于幂等块之后：同键异体仍按 9.1 报 409，重复取消由状态机拒绝
        // （与取消动作端点同一先例）
        if (isCancelKeyword(text(payload.get("text")))) {
            return cancelLatestActiveInstruction(caller, aircraftId);
        }

        // 文本与结构化二选一（评审 P0-10；详细设计 9.4）：无结构化命令时走
        // 别名规范化 → 意图路由 → 解析器目录
        String type = text(payload.get("type"));
        Map<String, Object> parameters = parametersOf(payload.get("parameters"));
        String rawText = text(payload.get("text"));
        if (type!=null && rawText!=null) throw new V2DomainException("INVALID_INSTRUCTION",400,"text 与 command 必须二选一");
        double now=((Number)aircraftMapper.findGroupStateAndTime(groupId).get("simulation_time_seconds")).doubleValue();
        if (type == null && rawText != null) {
            Map<String, Object> parsed = commandTextParser.parse(
                    rawText, text(aircraft.get("destination")),now);
            type = String.valueOf(parsed.get("type"));
            Map<String, Object> parsedParameters = parametersOf(parsed.get("parameters"));
            parsedParameters.put("normalizedText", parsed.get("normalizedText"));
            parameters = parsedParameters;
        } else if(type!=null) {
            parameters=StructuredCommandParser.parse(type,parameters,text(aircraft.get("destination")),now);
        }
        if (type == null || !catalog.createsInstruction(type)) {
            throw new V2DomainException("INVALID_INSTRUCTION", 400,
                    "指令类型缺失或不创建指令: " + type, Arrays.asList("type"));
        }
        String scheduling = validateRequestMode(text(payload.get("scheduling")));
        if(referenceValidator!=null) referenceValidator.validate(type,parameters,aircraft,now);

        String channel = catalog.channelOf(type);
        // §7.8 规则 2：能力槽指令（ID/DECOMP）必须先过 profile 门控（无 PUBLISHED
        // profile 不得建指令、不得下发 Adapter；ID 绝不回退为 IDENT）。
        // TAKEOFF/MISSED 等复合程序指令同属 SPECIAL 通道但不受此门控。
        if ("ID".equals(type) || "DECOMP".equals(type)) {
            capabilityProfileService.requirePublishedProfile(type, specialModeCode(type, parameters));
        }
        // P_LEVEL/P_TIME 按航段独立冲突键且需经 Adapter 写计划新版本
        // （评审 P0-11；command-catalog-v2.json 自述 BUSINESS_FIELD:LEG:{legId}:LEVEL）
        boolean planConstraintCommand = "P_LEVEL".equals(type) || "P_TIME".equals(type);
        String conflictKey = conflictKeyOf(type, channel, aircraftId, parameters);
        long sequence = queueService.allocateSequence(aircraftId, conflictKey);
        String predecessorId = null;

        // 前置指令：AFTER_COMPLETION 以当前同键在途/等待最新指令为前置（详细设计 6.2）
        if ("AFTER_COMPLETION".equals(scheduling)) {
            Map<String, Object> latest = mapper.findLatestByConflictKey(aircraftId, conflictKey);
            if (latest != null && !InstructionStateMachine.isTerminal(
                    String.valueOf(latest.get("status")))) {
                predecessorId = String.valueOf(latest.get("id"));
            }
        }

        String parsedPayload = jsonOf(parameters);
        if (rawText == null) {
            // 结构化提交时保存规范化命令文本（v1 raw_text 非空；命令报告同存原始与规范文本）
            rawText = type + " " + jsonOf(parametersOf(payload.get("parameters")));
        }
        if(rawText.length()>256)throw new V2DomainException("INVALID_INSTRUCTION",400,"指令文本不能超过 256 字符");
        String instructionId = UUID.randomUUID().toString();
        mapper.insertInstruction(instructionId, aircraftId, groupId, caller.terminalId(),
                type, channel, conflictKey, scheduling, "RECEIVED", sequence, predecessorId,
                rawText, parsedPayload, idempotencyKey, idempotencyRequestDigest);

        // 校验通过：RECEIVED → VALIDATED
        transition(instructionId, "RECEIVED", "VALIDATE");

        // P0-12 复合指令：SPECIAL 通道按 affectedChannels 建子指令并各占冲突键；
        // 父项单次载荷携带完整 affectedChannels
        if ("SPECIAL".equals(channel)) {
            for (String childId : compositeService.createChildren(
                    instructionId, aircraftId, groupId, caller.terminalId(), type,
                    catalog, queueService, rawText, parsedPayload)) {
                transition(childId, "RECEIVED", "VALIDATE");
            }
        }

        boolean adapterRequired = !"BUSINESS_FIELD".equals(channel) || planConstraintCommand;
        boolean blocked = false;

        Object scheduled = parameters.get("scheduledTimeSeconds");
        if (scheduled instanceof Number && ((Number)scheduled).doubleValue() >
                ((Number)aircraftMapper.findGroupStateAndTime(groupId).get("simulation_time_seconds")).doubleValue()) {
            blockerService.add(instructionId,"SCHEDULED_TIME_NOT_REACHED");
            blocked=true;
        }

        if ("AFTER_COMPLETION".equals(scheduling) && predecessorId != null) {
            blockerService.add(instructionId, "PREDECESSOR_ACTIVE");
            blocked = true;
        }

        Map<String, Object> group = aircraftMapper.findGroupStateAndTime(groupId);
        String groupState = String.valueOf(group.get("state"));
        if (!Arrays.asList("RUNNING", "PAUSED").contains(groupState)) {
            throw new V2DomainException("TRAINING_STATE_INVALID", 409,
                    "当前训练状态不允许提交指令: " + groupState, Arrays.asList("groupState"));
        }
        if ("PAUSED".equals(groupState)) {
            blockerService.add(instructionId, "TRAINING_PAUSED");
            blocked = true;
        }

        Map<String, Object> result;
        List<String> warnings = java.util.Collections.emptyList();
        if (blocked) {
            transition(instructionId, "VALIDATED", "BLOCK");
            result = mapper.findById(instructionId);
        } else if (adapterRequired) {
            // P0-6：BEGIN_DISPATCH 同事务占 slot + 建引导目标 + 写 INSTRUCTION_APPLY Outbox
            transition(instructionId, "VALIDATED", "BEGIN_DISPATCH");
            dispatchService.begin(instructionId);
            result = mapper.findById(instructionId);
        } else {
            // 纯业务字段指令：同事务写回业务字段并直达 COMPLETED
            // （详细设计 5.4/7.6；评审 P0-8）
            transition(instructionId, "VALIDATED", "DIRECT_EXECUTE");
            warnings = businessFieldService.apply(instructionId, aircraftId, groupId, type,
                    parameters);
            transition(instructionId, "EXECUTING", "COMPLETE");
            dispatchService.finalizeTerminal(instructionId, "COMPLETED");
            result = mapper.findById(instructionId);
        }
        result.put("blockingReasons", blockerService.activeReasons(instructionId));
        if (!warnings.isEmpty()) {
            result.put("warnings", warnings);
        }
        return result;
    }

    @Transactional
    public Map<String, Object> cancel(org.bluesky.training.common.CallerContext caller,
                                      String instructionId) {
        return cancel(caller,instructionId,null);
    }

    @Transactional
    public Map<String,Object> cancel(org.bluesky.training.common.CallerContext caller,String instructionId,Long expectedRevision) {
        terminalAccessPolicy.requireTerminalWrite(caller);
        Map<String, Object> instruction = requireInstruction(instructionId);
        aircraftMapper.lockById(String.valueOf(instruction.get("exercise_aircraft_id")));
        instruction=requireInstruction(instructionId);
        if(expectedRevision!=null) new org.bluesky.training.common.RevisionGuard().requireExpected(expectedRevision,((Number)instruction.get("revision")).longValue());
        String status = String.valueOf(instruction.get("status"));
        String groupId = String.valueOf(instruction.get("exercise_group_id"));
        terminalAccessPolicy.requireSameGroup(caller, groupId);
        requireCurrentResponsibility(caller,
                String.valueOf(instruction.get("exercise_aircraft_id")));
        // 取消 BLOCKED 直达 CANCELLED；EXECUTING 需先收 Adapter 确认（P10 接线），COMPLETED 不可取消
        if ("CANCELLED".equals(status)) return instruction;
        if ("DISPATCHING".equals(status) || "EXECUTING".equals(status)) {
            dispatchService.requestCancel(instructionId);
            instruction.put("cancelPending",true);
            return instruction;
        }
        transition(instructionId, status, "CANCEL");
        dispatchService.finalizeTerminal(instructionId, "CANCELLED");
        return mapper.findById(instructionId);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(String instructionId) {
        Map<String, Object> instruction = requireInstruction(instructionId);
        instruction.put("blockingReasons", blockerService.activeReasons(instructionId));
        instruction.put("guidanceTargets", mapper.guidanceOf(instructionId));
        return instruction;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String aircraftId) {
        List<Map<String, Object>> instructions = mapper.listByAircraft(aircraftId, 200);
        for (Map<String, Object> instruction : instructions) {
            instruction.put("blockingReasons", blockerService.activeReasons(
                    String.valueOf(instruction.get("id"))));
        }
        return instructions;
    }

    private void transition(String instructionId, String from, String event) {
        try {
            String target = stateMachine.transition(from, event);
            int changed = mapper.transitionStatus(instructionId, from, target);
            if (changed != 1) {
                throw new V2DomainException("REVISION_CONFLICT", 409,
                        "指令状态已被并发修改: " + instructionId);
            }
        } catch (IllegalStateException illegal) {
            throw new V2DomainException("TRAINING_STATE_INVALID", 409,
                    illegal.getMessage(), Arrays.asList("status"));
        }
    }

    /** 详细设计 6.4：提交指令前必须确认当前责任终端匹配。 */
    private void requireCurrentResponsibility(
            org.bluesky.training.common.CallerContext caller, String aircraftId) {
        Map<String, Object> assignment = aircraftMapper.findCurrentAssignment(aircraftId);
        if (assignment == null
                || !caller.terminalId().equals(String.valueOf(assignment.get("terminal_id")))) {
            throw new V2DomainException("AIRCRAFT_NOT_ASSIGNED", 403,
                    "非当前责任席位", Arrays.asList("terminalId"));
        }
    }

    private Map<String, Object> requireInstruction(String instructionId) {
        Map<String, Object> instruction = instructionId == null ? null
                : mapper.findById(instructionId);
        if (instruction == null) {
            throw new V2DomainException("TERMINAL_NOT_FOUND", 404, "指令不存在: " + instructionId);
        }
        return instruction;
    }

    public static String validateRequestMode(String scheduling) {
        if (scheduling == null) {
            return "REPLACE";
        }
        String mode = scheduling.trim().toUpperCase();
        if (!Arrays.asList("REPLACE", "AFTER_COMPLETION").contains(mode)) {
            throw new V2DomainException("INVALID_INSTRUCTION", 400,
                    "调度方式必须是 REPLACE/AFTER_COMPLETION: " + scheduling,
                    Arrays.asList("scheduling"));
        }
        return mode;
    }

    /**
     * 冲突键（评审 P0-11）：P_LEVEL/P_TIME 用解析器的
     * BUSINESS_FIELD:LEG:{legId}:LEVEL/TIME 模板按航段独立解析 legId；
     * 其余沿用目录规则（LATERAL/VERTICAL/SPEED:aircraftId、BUSINESS_FIELD:type:aircraftId）。
     */
    private String conflictKeyOf(String type, String channel, String aircraftId,
                                 Map<String, Object> parameters) {
        if ("P_LEVEL".equals(type) || "P_TIME".equals(type)) {
            Object template = parameters == null
                    ? null : parameters.get("conflictKeyTemplate");
            Object legPoint = parameters == null ? null : parameters.get("legPoint");
            if (template == null || legPoint == null) {
                throw new V2DomainException("INVALID_INSTRUCTION", 400,
                        type + " 缺少航段信息", Arrays.asList("legPoint"));
            }
            String legId = aircraftMapper.findCurrentLegIdByPoint(aircraftId,
                    String.valueOf(legPoint));
            if (legId == null) {
                throw new V2DomainException("INVALID_INSTRUCTION", 400,
                        "航空器当前计划中不存在航路点 " + legPoint
                                + "（缺少参考数据或非未来航段）", Arrays.asList("legPoint"));
            }
            return String.valueOf(template).replace("{legId}", legId);
        }
        return catalog.conflictKeysFor(type, aircraftId).get(0);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parametersOf(Object parameters) {
        if (parameters == null) {
            return new LinkedHashMap<>();
        }
        if (parameters instanceof Map) {
            return new LinkedHashMap<>((Map<String, Object>) parameters);
        }
        throw new V2DomainException("INVALID_INSTRUCTION", 400,
                "parameters 必须是对象", Arrays.asList("parameters"));
    }

    /** §7.8：ID 固定 modeCode=DEFAULT；DECOMP 必须显式携带 N/S/CLR。 */
    private static String specialModeCode(String type, Map<String, Object> parameters) {
        String mode = text(parameters.get("modeCode"));
        if (mode == null) {
            mode = text(parameters.get("mode"));
        }
        if (mode == null) {
            if ("ID".equals(type)) {
                return "DEFAULT";
            }
            throw new V2DomainException("INVALID_INSTRUCTION", 400,
                    "DECOMP 必须携带模式 N/S/CLR", Arrays.asList("modeCode"));
        }
        return mode.toUpperCase();
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    /** CANCEL 文本命令关键字：CANCEL / 取消（大小写不敏感，前后空白忽略）。 */
    private static boolean isCancelKeyword(String value) {
        if (value == null) {
            return false;
        }
        String trimmed = value.trim();
        return "CANCEL".equalsIgnoreCase(trimmed) || "取消".equals(trimmed);
    }

    private Map<String, Object> cancelLatestActiveInstruction(
            org.bluesky.training.common.CallerContext caller, String aircraftId) {
        Map<String, Object> active = mapper.findLatestActiveByAircraft(aircraftId);
        if (active == null) {
            throw new V2DomainException("TRAINING_STATE_INVALID", 409,
                    "该航空器当前没有可取消的活动指令", Arrays.asList("text"));
        }
        return cancel(caller, String.valueOf(active.get("id")));
    }

    private static String jsonOf(Object parameters) {
        if (parameters == null) {
            return "{}";
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(parameters);
        } catch (java.io.IOException e) {
            throw new V2DomainException("INVALID_INSTRUCTION", 400,
                    "parameters 序列化失败", Arrays.asList("parameters"));
        }
    }

    /**
     * 指令幂等请求摘要（详细设计 9.1）：对整个请求体做规范化 JSON 的
     * SHA-256——与 IdempotentHttpExecutor 的 HTTP 层语义一致，键排序保证
     * 字段顺序无关；aircraftRevision 等全部字段都参与摘要。
     */
    private static String instructionRequestDigest(Map<String, Object> payload) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper canonical =
                    new com.fasterxml.jackson.databind.ObjectMapper()
                            .configure(com.fasterxml.jackson.databind.MapperFeature
                                    .SORT_PROPERTIES_ALPHABETICALLY, true)
                            .configure(com.fasterxml.jackson.databind.SerializationFeature
                                    .ORDER_MAP_ENTRIES_BY_KEYS, true);
            byte[] body = canonical.writeValueAsBytes(payload == null
                    ? java.util.Collections.emptyMap() : payload);
            return org.bluesky.training.common.RequestDigest.sha256(
                    "POST", "aircraft-instructions", body);
        } catch (java.io.IOException e) {
            throw new V2DomainException("REQUEST_BODY_INVALID", 400, "请求体无法规范化");
        }
    }
}
