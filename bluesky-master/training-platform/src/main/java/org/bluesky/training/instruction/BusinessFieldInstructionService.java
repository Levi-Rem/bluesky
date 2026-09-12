package org.bluesky.training.instruction;

import org.bluesky.training.common.V2DomainException;
import org.bluesky.training.persistence.AircraftV2Mapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * P0-8（评审；详细设计 5.4/7.6）：纯业务字段指令同事务写回。
 * SQK/SSRMODE 更新 exercise_aircraft 当前值；NML 校验计划原值后恢复；
 * IDENT 激活识别并按仿真时钟登记到期时刻（由 TransponderIdentExpiryWatcher 清除）。
 * 任何校验失败整体回滚（指令与业务字段同一事务）。
 */
@Service
public class BusinessFieldInstructionService {

    private final AircraftV2Mapper aircraftMapper;

    public BusinessFieldInstructionService(AircraftV2Mapper aircraftMapper) {
        this.aircraftMapper = aircraftMapper;
    }

    /** 写回业务字段；返回随指令响应下发的警告（如 DUPLICATE_SQUAWK）。 */
    @Transactional
    public List<String> apply(String instructionId, String aircraftId, String groupId,
                              String type, Map<String, Object> parameters) {
        Map<String, Object> aircraft = aircraftMapper.findById(aircraftId);
        if (aircraft == null) {
            throw new V2DomainException("TERMINAL_NOT_FOUND", 404,
                    "航空器不存在: " + aircraftId);
        }
        String currentSquawk = text(aircraft.get("current_squawk"));
        String currentMode = text(aircraft.get("ssr_mode"));
        switch (type) {
            case "SQK": {
                // CLR：清除人工值，恢复计划应答机编码（详细设计 7.6）
                boolean clear = parameters != null
                        && "CLEAR".equals(String.valueOf(parameters.get("action")));
                String squawk = clear
                        ? text(aircraft.get("planned_squawk")) : requiredText(parameters, "squawk");
                if (squawk == null) {
                    throw new V2DomainException("INVALID_INSTRUCTION", 400,
                            "SQK CLR 需要计划应答机编码", Arrays.asList("plannedSquawk"));
                }
                int changed = aircraftMapper.updateTransponderState(
                        aircraftId, squawk, currentMode);
                requireApplied(changed, aircraftId);
                return clear ? Collections.emptyList()
                        : duplicateSquawkWarning(groupId, squawk, aircraftId);
            }
            case "SSRMODE": {
                String mode = requiredText(parameters, "ssrMode");
                int changed = aircraftMapper.updateTransponderState(
                        aircraftId, currentSquawk, mode);
                requireApplied(changed, aircraftId);
                return Collections.emptyList();
            }
            case "NML": {
                // 恢复计划原值（详细设计 7.6）：计划应答机编码必须存在，
                // 否则视为计划已变更，整体回滚
                String plannedSquawk = text(aircraft.get("planned_squawk"));
                if (plannedSquawk == null) {
                    throw new V2DomainException("INVALID_INSTRUCTION", 400,
                            "计划应答机编码缺失，无法恢复计划值",
                            Arrays.asList("plannedSquawk"));
                }
                int changed = aircraftMapper.updateTransponderState(
                        aircraftId, plannedSquawk, "C");
                requireApplied(changed, aircraftId);
                return Collections.emptyList();
            }
            case "IDENT": {
                Number duration = parameters == null
                        ? null : asNumber(parameters.get("durationSeconds"));
                int seconds = duration == null
                        ? TransponderCommandParsers.IDENT_DEFAULT_SECONDS
                        : duration.intValue();
                if (seconds < TransponderCommandParsers.IDENT_MIN_SECONDS
                        || seconds > TransponderCommandParsers.IDENT_MAX_SECONDS) {
                    throw new V2DomainException("INVALID_INSTRUCTION", 400,
                            "IDENT 持续时间配置范围 "
                                    + TransponderCommandParsers.IDENT_MIN_SECONDS + "–"
                                    + TransponderCommandParsers.IDENT_MAX_SECONDS + " 秒: "
                                    + seconds,
                            Arrays.asList("durationSeconds"));
                }
                double now = simulationTimeOf(groupId);
                int changed = aircraftMapper.updateTransponderIdent(aircraftId, true,
                        BigDecimal.valueOf(now + seconds).setScale(3,
                                java.math.RoundingMode.HALF_UP));
                requireApplied(changed, aircraftId);
                return Collections.emptyList();
            }
            default:
                // 未识别的纯业务字段命令：不得静默无效果（详细设计 5.4）
                throw new V2DomainException("INVALID_INSTRUCTION", 400,
                        "纯业务字段命令缺少写回实现: " + type, Arrays.asList("type"));
        }
    }

    private List<String> duplicateSquawkWarning(String groupId, String squawk,
                                                String aircraftId) {
        int others = aircraftMapper.countActiveSquawkInGroup(groupId, squawk, aircraftId);
        if (others > 0) {
            return Collections.singletonList("DUPLICATE_SQUAWK");
        }
        return Collections.emptyList();
    }

    private double simulationTimeOf(String groupId) {
        Map<String, Object> group = aircraftMapper.findGroupStateAndTime(groupId);
        if (group == null) {
            return 0.0;
        }
        Number seconds = asNumber(group.get("simulation_time_seconds"));
        return seconds == null ? 0.0 : seconds.doubleValue();
    }

    private static void requireApplied(int changed, String aircraftId) {
        if (changed != 1) {
            throw new V2DomainException("REVISION_CONFLICT", 409,
                    "航空器状态已被并发修改: " + aircraftId, Arrays.asList("aircraftRevision"));
        }
    }

    private static String requiredText(Map<String, Object> parameters, String key) {
        String value = parameters == null ? null : text(parameters.get(key));
        if (value == null) {
            throw new V2DomainException("INVALID_INSTRUCTION", 400,
                    "缺少 " + key, Arrays.asList(key));
        }
        return value;
    }

    private static Number asNumber(Object value) {
        return value instanceof Number ? (Number) value : null;
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() || "null".equals(text) ? null : text;
    }
}
