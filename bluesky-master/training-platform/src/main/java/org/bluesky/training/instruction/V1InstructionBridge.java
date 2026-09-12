package org.bluesky.training.instruction;

import org.bluesky.training.adapter.SimulationGateway;
import org.bluesky.training.persistence.AircraftV2Mapper;
import org.bluesky.training.persistence.InstructionV2Mapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 迁移期指令桥：Protocol 2.0 引擎实例未注册时，DISPATCHING 指令经 v1
 * SimulationGateway 下发，成功后走 onAdapterApplied 完成 v2 确认语义
 * （引导激活 + REPLACE 清队）。Protocol 2.0 接线后由该属性关闭。
 */
@Component
@ConditionalOnProperty(name = "bluesky.adapter.v1-bridge-enabled",
        havingValue = "true", matchIfMissing = false)
public class V1InstructionBridge {

    private static final Logger log = LoggerFactory.getLogger(V1InstructionBridge.class);

    private final InstructionV2Mapper mapper;
    private final AircraftV2Mapper aircraftMapper;
    private final SimulationGateway gateway;
    private final InstructionDispatchService dispatchService;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper =
            new com.fasterxml.jackson.databind.ObjectMapper();

    public V1InstructionBridge(InstructionV2Mapper mapper, AircraftV2Mapper aircraftMapper,
                               SimulationGateway gateway,
                               InstructionDispatchService dispatchService) {
        this.mapper = mapper;
        this.aircraftMapper = aircraftMapper;
        this.gateway = gateway;
        this.dispatchService = dispatchService;
    }

    @Scheduled(fixedDelay = 500)
    public void dispatchPending() {
        for (Map<String, Object> instruction : mapper.findDispatchingInstructions(20)) {
            String instructionId = String.valueOf(instruction.get("id"));
            // 复合子项不单独派发（评审 P0-12：父项单次载荷携带全部受影响通道）；
            // 子项仅随父项 CONFIRM_APPLIED 进入 EXECUTING
            if (mapper.findParentIdOf(instructionId) != null) {
                continue;
            }
            try {
                dispatchOnce(instruction);
            } catch (RuntimeException failure) {
                // 桥不可执行的指令（如 Protocol 1.0 不支持的类型）必须终结并释放占位，
                // 否则同冲突键永远 CHANNEL_DISPATCH_IN_PROGRESS（验收级联根因）
                log.warn("v1 桥下发失败并终结指令 instruction={} reason={}", instructionId,
                        failure.getMessage());
                try {
                    dispatchService.handleBridgeRejected(instructionId, failure.getMessage());
                } catch (RuntimeException terminal) {
                    log.warn("终结桥拒绝指令失败 instruction={} reason={}", instructionId,
                            terminal.getMessage());
                }
            }
        }
    }

    private void dispatchOnce(Map<String, Object> instruction) {
        String instructionId = String.valueOf(instruction.get("id"));
        String aircraftId = String.valueOf(instruction.get("exercise_aircraft_id"));
        Map<String, Object> aircraft = aircraftMapper.findById(aircraftId);
        if (aircraft == null) {
            return;
        }
        String callsign = String.valueOf(aircraft.get("callsign"));
        Map<String, Object> parameters = parseParameters(instruction);
        gateway.executeInstruction(toCommand(callsign,
                String.valueOf(instruction.get("instruction_type")), parameters,
                instructionId));
        // v1 网关同步返回即视为技术确认（1.0 REQ/REP 语义），随后走 v2 确认事务
        dispatchService.onAdapterApplied(instructionId);
    }

    private static EngineInstructionCommand toCommand(String callsign, String type,
                                                      Map<String, Object> parameters,
                                                      String commandId) {
        return new EngineInstructionCommand(
                callsign,
                type,
                dbl(parameters, "magneticHeadingDeg", "headingDegrees"),
                dbl(parameters, "altitudeFtMsl", "altitudeFeet"),
                dbl(parameters, "verticalRateFpm", "verticalSpeedFeetPerMinute"),
                dbl(parameters, "indicatedAirspeedKt", "speedKnots"),
                dbl(parameters, "mach"),
                str(parameters, "targetPoint", "waypoint", "resumePoint"),
                routeOf(parameters))
                .withCommandId(commandId)
                // 复杂引导类型（TAKEOFF/ILS/HOLD/ORBIT…）按完整 v2 参数字典执行
                .withParametersJson(jsonOf(parameters));
    }

    private static List<String> routeOf(Map<String, Object> parameters) {
        Object route = parameters.get("route");
        if (route instanceof java.util.List) {
            java.util.List<String> points = new java.util.ArrayList<>();
            for (Object point : (java.util.List<?>) route) {
                points.add(String.valueOf(point));
            }
            return points;
        }
        return java.util.Collections.emptyList();
    }

    private static String jsonOf(Map<String, Object> parameters) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(parameters);
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            return "{}";
        }
    }

    private static Double dbl(Map<String, Object> parameters, String... keys) {
        for (String key : keys) {
            Object value = parameters.get(key);
            if (value instanceof Number) {
                return ((Number) value).doubleValue();
            }
        }
        return null;
    }

    private static String str(Map<String, Object> parameters, String... keys) {
        for (String key : keys) {
            Object value = parameters.get(key);
            if (value != null && !String.valueOf(value).trim().isEmpty()) {
                return String.valueOf(value);
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseParameters(Map<String, Object> instruction) {
        Object payload = instruction.get("parsed_payload");
        if (payload instanceof String) {
            try {
                Object parsed = objectMapper.readValue((String) payload, Object.class);
                if (parsed instanceof Map) {
                    return (Map<String, Object>) parsed;
                }
            } catch (java.io.IOException ignored) {
                // 落到空参数
            }
        } else if (payload instanceof Map) {
            return (Map<String, Object>) payload;
        }
        return java.util.Collections.emptyMap();
    }
}
