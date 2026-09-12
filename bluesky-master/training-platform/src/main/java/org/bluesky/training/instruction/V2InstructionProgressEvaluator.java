package org.bluesky.training.instruction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.bluesky.training.event.EventStreamService;
import org.bluesky.training.persistence.InstructionV2Mapper;
import org.bluesky.training.report.ReportWriteService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * P09：v2 指令收敛评估器（详细设计 2.2 §6.3.3/6.3.4）。
 * Adapter 状态帧驱动 EXECUTING 指令：目标容差 + 仿真稳定窗 + 最大执行预算；
 * 收敛 → COMPLETED（清引导目标、写 TARGET_REACHED 飞行报告、释放后继）；
 * 预算耗尽 → FAILED(TARGET_NOT_REACHED)。预算按仿真时间推进，暂停自然冻结。
 * 复合父项不在此评估（子项终态经聚合推进父状态机，评审 P0-12）。
 */
@Service
public class V2InstructionProgressEvaluator {

    private static final Logger log = LoggerFactory.getLogger(V2InstructionProgressEvaluator.class);

    private final InstructionV2Mapper mapper;
    private final InstructionDispatchService dispatchService;
    private final ReportWriteService reportWriteService;
    private final EventStreamService eventStreamService;
    private final org.bluesky.training.mapdata.MapDataService mapDataService;
    private final org.bluesky.training.aircraft.FlightPlanService flightPlanService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 每指令稳定窗与执行起点（仿真秒）；收敛/失败后清理。 */
    private final Map<String, InstructionCompletionService.StableWindow> windows =
            new ConcurrentHashMap<>();
    private final Map<String, Double> executingSinceSim = new ConcurrentHashMap<>();
    /** §6.3 动态预算在执行起点冻结（预计到达时间不随后续接近重算）。 */
    private final Map<String, Double> frozenBudgets = new ConcurrentHashMap<>();
    private final Map<String, Double> relativeHeadingBase = new ConcurrentHashMap<>();

    public V2InstructionProgressEvaluator(InstructionV2Mapper mapper,
                                          InstructionDispatchService dispatchService,
                                          ReportWriteService reportWriteService,
                                          EventStreamService eventStreamService,
                                          org.bluesky.training.mapdata.MapDataService mapDataService,
                                          org.bluesky.training.aircraft.FlightPlanService flightPlanService,
                                          org.springframework.transaction.PlatformTransactionManager transactionManager) {
        this.mapper = mapper;
        this.dispatchService = dispatchService;
        this.reportWriteService = reportWriteService;
        this.eventStreamService = eventStreamService;
        this.mapDataService = mapDataService;
        this.flightPlanService = flightPlanService;

    }

    /** 每架航空器状态帧评估一次；Adapter 帧事件同步写飞行报告。 */
    @Transactional
    public void evaluate(String aircraftId, JsonNode state, double simulationTimeSeconds) {
        if (state == null) {
            return;
        }
        recordFrameEvents(aircraftId, state, simulationTimeSeconds);
        for (Map<String, Object> instruction : mapper.findExecutingByAircraft(aircraftId)) {
            String instructionId = String.valueOf(instruction.get("id"));
            if (!mapper.childrenOf(instructionId).isEmpty()) {
                continue; // 复合父项由子项聚合（recomputeCompositeParentOf）
            }
            try {
                evaluateOne(instruction, state, simulationTimeSeconds);
            } catch (RuntimeException failure) {
                log.warn("v2 指令收敛评估失败 instruction={} reason={}",
                        instructionId, failure.getMessage());
            }
        }
    }

    private void evaluateOne(Map<String, Object> instruction, JsonNode state,
                             double simulationTimeSeconds) {
        String instructionId = String.valueOf(instruction.get("id"));
        String type = String.valueOf(instruction.get("instruction_type"));
        String channel = String.valueOf(instruction.get("control_channel"));
        String aircraftId = String.valueOf(instruction.get("exercise_aircraft_id"));
        // 复合子项不单独派发：Adapter 回执携带父项 commandId（评审 P0-12）；
        // 父子关系存于 composite_instruction_child（parent_instruction_id 列不承载）
        String parentId = mapper.findParentIdOf(instructionId);
        String receiptId = parentId == null ? instructionId : parentId;
        Map<String, Object> parameters = parseParameters(
                String.valueOf(instruction.get("parsed_payload")));

        Boolean withinTolerance = withinTolerance(type, channel, parameters,
                instructionId, receiptId, state);
        if (withinTolerance == null) {
            return; // 无收敛判据（ORBIT 等不定指令）：保持 EXECUTING 直至替代/取消
        }
        executingSinceSim.putIfAbsent(instructionId, simulationTimeSeconds);
        double budget = frozenBudgets.computeIfAbsent(instructionId,
                key -> budgetSeconds(type, parameters, state));
        double elapsed = simulationTimeSeconds - executingSinceSim.get(instructionId);
        boolean stable = windowOf(instructionId, type).evaluate(withinTolerance,
                simulationTimeSeconds);
        if (stable) {
            complete(instruction, aircraftId, channel, simulationTimeSeconds);
        } else if (InstructionCompletionService.budgetExceeded(budget, elapsed)) {
            terminateBudgetExceeded(instruction, aircraftId, channel,
                    budget, elapsed, simulationTimeSeconds);
        }
    }

    /** 返回 null 表示该类型无平台侧判据（依赖 Adapter 回执或不定执行）。 */
    private Boolean withinTolerance(String type, String channel,
                                    Map<String, Object> parameters, String instructionId,
                                    String receiptId, JsonNode state) {
        switch (type) {
            case "HDG":
            case "LEFT":
            case "RIGHT":
                return headingWithin(type, parameters, instructionId, receiptId, state);
            case "ALT": {
                Double target = dbl(parameters.get("altitudeFtMsl"));
                return target == null ? false
                        : Math.abs(state.path("altitudeFeet").asDouble() - target) <= 100.0;
            }
            case "VS": {
                Double target = dbl(parameters.get("verticalRateFpm"));
                return target == null ? false
                        : Math.abs(state.path("verticalSpeedFeetPerMinute").asDouble() - target)
                                <= 150.0;
            }
            case "SPD": {
                Double target = dbl(parameters.get("indicatedAirspeedKt"));
                return target == null ? false
                        : Math.abs(state.path("speedKnots").asDouble() - target) <= 5.0;
            }
            case "MACH": {
                Double target = dbl(parameters.get("mach"));
                return target == null ? false
                        : Math.abs(state.path("mach").asDouble() - target) <= 0.01;
            }
            case "DCT":
            case "RESUME":
                JsonNode directTo = state.path("directTo");
                return directTo.path("commandId").asText("").equals(receiptId)
                        && directTo.path("passed").asBoolean(false);
            case "RTE":
            case "SIDSTAR":
                JsonNode routeChange = state.path("routeChange");
                return routeChange.path("commandId").asText("").equals(receiptId)
                        && routeChange.path("activated").asBoolean(false);
            case "OFFSET":
                return receiptDone(state, "offsetApplied", receiptId);
            case "NSPEED":
                return receiptDone(state,"managedSpeedRestored",receiptId);
            case "ORBIT":
                return receiptDone(state,"orbitActive",receiptId);
            case "VOR":
                return receiptDone(state, "vorEstablished", receiptId);
            case "P_LEVEL":
            case "P_TIME":
                return receiptDone(state, "planConstraintApplied", receiptId);
            case "HOLD":
                return receiptDone(state, "holdEstablished", receiptId);
            case "TAKEOFF":
                return takeoffWithin(channel, parameters, receiptId, state);
            case "ILS":
                JsonNode ils = state.path("receipts").path("ils");
                if (!ils.path("commandId").asText("").equals(receiptId)) {
                    return false;
                }
                double landingSpeed = parameters.get("landingStopSpeedKt") instanceof Number
                        ? ((Number)parameters.get("landingStopSpeedKt")).doubleValue() : 0.0;
                return ils.path("landed").asBoolean(false)
                        && state.path("groundSpeedKnots").asDouble(state.path("speedKnots").asDouble(Double.MAX_VALUE)) <= landingSpeed;
            case "MISSED":
                JsonNode missed = state.path("receipts").path("missed");
                if (!missed.path("commandId").asText("").equals(receiptId)) {
                    return false;
                }
                return "VERTICAL".equals(channel)
                        ? missed.path("climbEstablished").asBoolean(false)
                        : missed.path("lateralEngaged").asBoolean(false);
            default:
                return null;
        }
    }

    private Boolean takeoffWithin(String channel, Map<String, Object> parameters,
                                  String receiptId, JsonNode state) {
        JsonNode takeoff = state.path("receipts").path("takeoff");
        if (!takeoff.path("commandId").asText("").equals(receiptId)) {
            return false;
        }
        if ("VERTICAL".equals(channel)) {
            Double target = dbl(parameters.get("targetAltitudeFtMsl"));
            return target == null ? false
                    : Math.abs(state.path("altitudeFeet").asDouble() - target) <= 300.0;
        }
        if ("SPEED".equals(channel)) {
            return Math.abs(state.path("speedKnots").asDouble()
                    - takeoff.path("targetSpeedKt").asDouble(Double.NaN)) <= 10.0;
        }
        // LATERAL：离地即视为保持起飞航迹并沿计划航路飞行（LNAV 会随即转向，
        // 不与初始航向比较）
        return takeoff.path("airborne").asBoolean(false);
    }

    private boolean headingWithin(String type, Map<String, Object> parameters,
                                  String instructionId, String receiptId, JsonNode state) {
        Double target = dbl(parameters.get("magneticHeadingDeg"));
        if (target == null && "ABSOLUTE".equals(parameters.get("mode"))) {
            target = dbl(parameters.get("valueDeg"));
        }
        if (target == null && ("LEFT".equals(type) || "RIGHT".equals(type))) {
            // 优先采用引擎回执的执行时刻目标航向：RELATIVE 基准是引擎执行
            // 瞬间的航向，用状态帧首航向猜测在高倍率下会漂移数度
            JsonNode heading = state.path("receipts").path("headingExecuted");
            if (heading.path("commandId").asText("").equals(receiptId)
                    && heading.hasNonNull("targetHeadingDeg")) {
                target = heading.path("targetHeadingDeg").asDouble();
            }
        }
        if (target == null) {
            // LEFT/RIGHT 相对模式兜底：以首次评估的航向为基准解析绝对目标
            Double base = relativeHeadingBase.computeIfAbsent(instructionId,
                    key -> state.path("headingDegrees").asDouble());
            double value = dbl(parameters.get("valueDeg"));
            target = (base + ("RIGHT".equals(type) ? value : -value) + 720.0) % 360.0;
        }
        double difference = Math.abs(state.path("headingDegrees").asDouble() - target);
        return Math.min(difference, 360.0 - difference) <= 2.0;
    }

    private static boolean receiptDone(JsonNode state, String receiptKey, String instructionId) {
        JsonNode receipt = state.path("receipts").path(receiptKey);
        return receipt.path("commandId").asText("").equals(instructionId);
    }

    private void complete(Map<String, Object> instruction, String aircraftId, String channel,
                          double simulationTimeSeconds) {
        String instructionId = String.valueOf(instruction.get("id"));
        if (mapper.transitionStatus(instructionId, "EXECUTING", "COMPLETED") != 1) {
            return; // 并发终态（取消/替代先行）以状态机为准
        }
        mapper.clearActiveGuidance(aircraftId, channel);
        cleanup(instructionId);
        reportWriteService.writeFlightReport(
                String.valueOf(instruction.get("exercise_group_id")), aircraftId,
                String.valueOf(instruction.get("source_terminal_id")), "TARGET_REACHED",
                "target-reached:" + instructionId,
                "指令收敛完成: " + instruction.get("raw_text"), simulationTimeSeconds);
        // RTE/SIDSTAR 收敛 = Adapter 已采用新航路：落对应的新计划版本（只增版本）
        // 命令报告由 finalizeTerminal 终态收口统一写入（详细设计 §5.5）
        dispatchService.finalizeTerminal(instructionId, "COMPLETED");
        publish(instructionId);
    }

    private void terminateBudgetExceeded(Map<String, Object> instruction, String aircraftId,
                                         String channel, double budget, double elapsed,
                                         double simulationTimeSeconds) {
        String instructionId = String.valueOf(instruction.get("id"));
        if (mapper.terminateWithReason(instructionId, "EXECUTING", "FAILED",
                "TARGET_NOT_REACHED",
                "目标在最大执行预算 " + (long) budget + "s 内未达到（实际 " + (long) elapsed + "s）")
                != 1) {
            return;
        }
        mapper.clearActiveGuidance(aircraftId, channel);
        cleanup(instructionId);
        dispatchService.finalizeTerminal(instructionId, "FAILED");
        publish(instructionId);
    }

    /** Adapter 帧事件（{type,sourceEventId,detail} 数组）→ 飞行报告（详细设计 §5.5）。 */
    private void recordFrameEvents(String aircraftId, JsonNode state,
                                   double simulationTimeSeconds) {
        JsonNode events = state.path("events");
        if (!events.isArray() || events.size() == 0) {
            return;
        }
        // LANDED 等事件发生在指令全部终态之后：组归属取航空器行，不得以
        // 存在 EXECUTING 指令为前提（否则落地事件必然丢失）
        String groupId = mapper.findGroupIdOfAircraft(aircraftId);
        if (groupId == null) {
            return;
        }
        for (JsonNode event : events) {
            String eventType = event.path("type").asText("");
            String sourceEventId = event.path("sourceEventId").asText("");
            if (eventType.isEmpty() || sourceEventId.isEmpty()) {
                continue;
            }
            reportWriteService.writeFlightReport(groupId, aircraftId, null, eventType,
                    sourceEventId, event.path("detail").asText(null), simulationTimeSeconds);
        }
    }

    private InstructionCompletionService.StableWindow windowOf(String instructionId,
                                                                String type) {
        return windows.computeIfAbsent(instructionId,
                key -> new InstructionCompletionService.StableWindow(stableSeconds(type)));
    }

    /**
     * §6.3 动态预算：DCT/RESUME/OFFSET/VOR = 预计到达×2+120s（上限 3600）；
     * ALT/VS = max(300, 爬升预测×2+60)（上限 3600）；HOLD 以默认表 900s 为
     * 下限、按定位点距离延长（远离定位点签发时转回+入位需更长时间）；
     * 其余取默认表。
     */
    private double budgetSeconds(String type, Map<String, Object> parameters,
                                 JsonNode state) {
        if ("DCT".equals(type) || "RESUME".equals(type) || "VOR".equals(type)
                || "OFFSET".equals(type)) {
            String code = "DCT".equals(type) ? str(parameters.get("targetPoint"))
                    : "RESUME".equals(type) ? str(parameters.get("resumePoint"))
                    : str(parameters.get("station"));
            Double eta = etaSecondsTo(code, state);
            if (eta != null) {
                return Math.min(3600.0, eta * 2.0 + 120.0);
            }
            return InstructionCompletionService.defaultBudgetSeconds(type);
        }
        if ("HOLD".equals(type)) {
            Double eta = etaSecondsTo(str(parameters.get("fixPoint")), state);
            if (eta != null) {
                double legSeconds = dbl(parameters.get("legSeconds")) == null
                        ? 60.0 : dbl(parameters.get("legSeconds"));
                return Math.min(3600.0,
                        Math.max(900.0, eta * 2.0 + 240.0 + legSeconds * 2.0));
            }
            return InstructionCompletionService.defaultBudgetSeconds(type);
        }
        if ("ALT".equals(type) || "VS".equals(type)) {
            Double target = dbl(parameters.get("altitudeFtMsl"));
            if (target != null) {
                double deltaFeet = Math.abs(target - state.path("altitudeFeet").asDouble());
                double climbSeconds = deltaFeet / 1500.0 * 60.0; // 名义爬升率 1500fpm
                return Math.min(3600.0, Math.max(300.0, climbSeconds * 2.0 + 60.0));
            }
        }
        return InstructionCompletionService.defaultBudgetSeconds(type);
    }

    /** 目标点预计到达时间（秒）；点不可解析时返回 null 走默认预算。 */
    private Double etaSecondsTo(String code, JsonNode state) {
        if (code == null) {
            return null;
        }
        try {
            org.bluesky.training.mapdata.RuntimeNavigationPoint point =
                    mapDataService.catalog().resolve(code);
            double distanceNm = distanceNm(state.path("latitude").asDouble(),
                    state.path("longitude").asDouble(),
                    point.getLatitude(), point.getLongitude());
            double speedKts = Math.max(80.0, state.path("speedKnots").asDouble(250.0));
            return distanceNm / speedKts * 3600.0;
        } catch (RuntimeException notResolvable) {
            return null;
        }
    }

    private static double distanceNm(double lat1, double lon1, double lat2, double lon2) {
        // 1° 纬度 ≈ 60NM（等距圆柱近似）
        double meanLat = Math.toRadians((lat1 + lat2) / 2.0);
        double dLat = (lat2 - lat1) * 60.0;
        double dLon = (lon2 - lon1) * 60.0 * Math.cos(meanLat);
        return Math.sqrt(dLat * dLat + dLon * dLon);
    }

    /** 回执型判据 3s 防抖；数值收敛 10s 稳定窗（详细设计 6.3.3）。 */
    private static double stableSeconds(String type) {
        if ("ILS".equals(type)) return 5.0;
        switch (type) {
            case "ALT":
            case "VS":
            case "SPD":
            case "MACH":
            case "TAKEOFF":
                return 10.0;
            default:
                return 3.0;
        }
    }

    private void cleanup(String instructionId) {
        windows.remove(instructionId);
        executingSinceSim.remove(instructionId);
        frozenBudgets.remove(instructionId);
        relativeHeadingBase.remove(instructionId);
    }

    private void publish(String instructionId) {
        Map<String, Object> current = mapper.findById(instructionId);
        if (current != null) {
            eventStreamService.publishAfterCommit("instruction-upserted", current);
        }
    }

    private Map<String, Object> parseParameters(String parsedPayload) {
        if (parsedPayload == null || "null".equals(parsedPayload)) {
            return java.util.Collections.emptyMap();
        }
        try {
            return objectMapper.readValue(parsedPayload, Map.class);
        } catch (java.io.IOException invalid) {
            return java.util.Collections.emptyMap();
        }
    }

    private static Double dbl(Object value) {
        return value instanceof Number ? ((Number) value).doubleValue() : null;
    }

    private static String str(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() || "null".equals(text) ? null : text;
    }
}
