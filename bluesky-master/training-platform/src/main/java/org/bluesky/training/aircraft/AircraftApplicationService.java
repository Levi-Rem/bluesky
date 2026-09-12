package org.bluesky.training.aircraft;

import org.bluesky.training.common.TerminalAccessPolicy;
import org.bluesky.training.common.TransactionalOutboxService;
import org.bluesky.training.common.V2DomainException;
import org.bluesky.training.persistence.AircraftV2Mapper;
import org.bluesky.training.persistence.OutboxEventRow;
import org.bluesky.training.reference.ReferenceSnapshotService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * P07：计划资源事务入口（详细设计 5.2.3）。
 * 创建事务只写 PLANNED，不提前调用 Adapter；重复 Squawk 仅警告；
 * 未出现计划的取消 = 同事务直达 DELETED（详细设计 2.2 §5.2.9）。
 */
@Service
public class AircraftApplicationService {

    private final AircraftV2Mapper aircraftMapper;
    private final FlightPlanService flightPlanService;
    private final InitialStateTemplateService templateService;
    private final TerminalAccessPolicy terminalAccessPolicy;
    private final TransactionalOutboxService outboxService;
    private final ReferenceSnapshotService referenceSnapshotService;

    /** 纯规则测试/迁移期兼容构造；未注入快照门禁时不执行可选校验。 */
    public AircraftApplicationService(AircraftV2Mapper aircraftMapper,
                                      FlightPlanService flightPlanService,
                                      InitialStateTemplateService templateService,
                                      TerminalAccessPolicy terminalAccessPolicy,
                                      TransactionalOutboxService outboxService) {
        this(aircraftMapper, flightPlanService, templateService, terminalAccessPolicy,
                outboxService, null);
    }

    @Autowired
    public AircraftApplicationService(AircraftV2Mapper aircraftMapper,
                                      FlightPlanService flightPlanService,
                                      InitialStateTemplateService templateService,
                                      TerminalAccessPolicy terminalAccessPolicy,
                                      TransactionalOutboxService outboxService,
                                      ReferenceSnapshotService referenceSnapshotService) {
        this.aircraftMapper = aircraftMapper;
        this.flightPlanService = flightPlanService;
        this.templateService = templateService;
        this.terminalAccessPolicy = terminalAccessPolicy;
        this.outboxService = outboxService;
        this.referenceSnapshotService = referenceSnapshotService;
    }

    @Transactional
    public Map<String, Object> createPlan(org.bluesky.training.common.CallerContext caller,
                                          String groupId, Map<String, Object> request) {
        terminalAccessPolicy.requireSameGroup(caller, groupId);
        requireGroupExists(groupId);
        requireGroupStateAllowsPlan(groupId);
        if (referenceSnapshotService != null) {
            referenceSnapshotService.verifyPinnedSnapshotIfPresent(groupId);
        }

        Map<String, Object> aircraftSpec = requireSection(request, "aircraft");
        Map<String, Object> planSpec = requireSection(request, "flightPlan");
        String aircraftType = requiredCode(aircraftSpec.get("aircraftType"),
                "aircraft.aircraftType", "^[A-Z0-9-]{2,16}$");
        String wakeCategory = requiredCode(aircraftSpec.get("wakeCategory"),
                "aircraft.wakeCategory", "^[LMHJ]$");
        String origin = requiredCode(planSpec.get("origin"),
                "flightPlan.origin", "^[A-Z0-9]{3,8}$");
        String destination = requiredCode(planSpec.get("destination"),
                "flightPlan.destination", "^[A-Z0-9]{3,8}$");
        Object fakeValue = aircraftSpec.get("isFake");
        if (fakeValue != null && !(fakeValue instanceof Boolean)) {
            throw new V2DomainException("INVALID_INSTRUCTION", 400,
                    "aircraft.isFake 必须是布尔值", Arrays.asList("aircraft.isFake"));
        }
        Map<String, Object> initialState = templateService.completeInitialState(
                section(request, "initialState"),
                aircraftType, origin, groupId);

        String callsign = AircraftValidator.normalizeCallsign(text(aircraftSpec.get("callsign")));
        String icao24 = upper(text(aircraftSpec.get("icao24")));
        AircraftValidator.validateIcao24(icao24);
        String plannedSquawk = text(planSpec.get("plannedSquawk"));
        AircraftValidator.validatePlannedSquawk(plannedSquawk);
        String ssrMode = requiredCode(planSpec.get("ssrMode"),
                "flightPlan.ssrMode", "^[AC]$");
        if (request.containsKey("appearanceDelaySeconds") && request.containsKey("targetAppearanceSimulationTimeSeconds"))
            throw new V2DomainException("INVALID_INSTRUCTION",400,"相对出现延迟与绝对出现时刻只能提供一个");
        double targetAppearance;
        if (request.containsKey("appearanceDelaySeconds")) {
            double delay=number(request.get("appearanceDelaySeconds"),"appearanceDelaySeconds");
            if(delay<0)throw new V2DomainException("INVALID_INSTRUCTION",400,"出现延迟不能小于零");
            targetAppearance=((Number)aircraftMapper.findGroupStateAndTime(groupId).get("simulation_time_seconds")).doubleValue()+delay;
        } else targetAppearance = number(request.get("targetAppearanceSimulationTimeSeconds"),
                "targetAppearanceSimulationTimeSeconds");
        requireTargetAppearanceNotInPast(groupId, targetAppearance);

        if (aircraftMapper.findIdByGroupAndCallsign(groupId, callsign) != null) {
            throw new V2DomainException("DUPLICATE_CALLSIGN", 409,
                    "训练组内已存在同呼号未删除航空器: " + callsign,
                    Arrays.asList("aircraft.callsign"));
        }
        if (icao24 != null && aircraftMapper.countActiveByIcao24(groupId, icao24) > 0) {
            throw new V2DomainException("DUPLICATE_CALLSIGN", 409,
                    "训练组内已存在相同 ICAO24: " + icao24, Arrays.asList("aircraft.icao24"));
        }
        boolean duplicateSquawk = aircraftMapper.countActiveBySquawk(groupId, plannedSquawk) > 0;

        String aircraftId = UUID.randomUUID().toString();
        String responsibleTerminal = text(request.get("assignedTerminalId"));
        if (responsibleTerminal == null) {
            responsibleTerminal = caller.terminalId();
        }
        requireEnabledTerminalInGroup(groupId, responsibleTerminal);
        try {
            aircraftMapper.insertPlannedAircraft(aircraftId, groupId, responsibleTerminal, callsign,
                    aircraftType, wakeCategory, origin, destination,
                    number(initialState.get("latitudeDeg"), "initialState.latitudeDeg"),
                    number(initialState.get("longitudeDeg"), "initialState.longitudeDeg"),
                    number(initialState.get("trueHeadingDeg"), "initialState.trueHeadingDeg"),
                    number(initialState.get("altitudeFtMsl"), "initialState.altitudeFtMsl"),
                    number(initialState.get("indicatedAirspeedKt"), "initialState.indicatedAirspeedKt"),
                    Boolean.TRUE.equals(fakeValue),
                    icao24, plannedSquawk, ssrMode, BigDecimal.valueOf(targetAppearance));
        } catch (DuplicateKeyException duplicate) {
            throw new V2DomainException("DUPLICATE_CALLSIGN", 409,
                    "训练组内呼号或 ICAO24 已被并发创建", Arrays.asList("aircraft.callsign"));
        }

        List<String> route = routeOf(planSpec.get("route"));
        Map<String, Object> plan = flightPlanService.createVersion(aircraftId,
                origin, destination,
                plannedSquawk, ssrMode,
                intOrNull(planSpec.get("cruiseAltitudeFtMsl"),
                        "flightPlan.cruiseAltitudeFtMsl"),
                intOrNull(planSpec.get("cruiseIndicatedAirspeedKt"),
                        "flightPlan.cruiseIndicatedAirspeedKt"), route);

        aircraftMapper.insertAssignment(UUID.randomUUID().toString(), aircraftId,
                responsibleTerminal);

        outboxService.enqueueBusinessEvent(OutboxEventRow.businessEvent(
                UUID.randomUUID().toString(), groupId, "aircraft.created",
                jsonOf("aircraftId", aircraftId, "callsign", callsign,
                        "lifecycle", "PLANNED")));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", aircraftId);
        body.put("callsign", callsign);
        body.put("lifecycle", "PLANNED");
        body.put("revision", 1L);
        body.put("initialState", initialState);
        body.put("flightPlan", plan);
        body.put("responsibleTerminalId", responsibleTerminal);
        List<Map<String, Object>> warnings = new ArrayList<>();
        Map<String, Object> warning = AircraftValidator.collectWarnings(duplicateSquawk);
        if (!warning.isEmpty()) {
            warnings.add(warning);
        }
        body.put("warnings", warnings);
        return body;
    }

    @Transactional
    public Map<String, Object> get(org.bluesky.training.common.CallerContext caller,
                                   String aircraftId) {
        Map<String, Object> aircraft = requireAircraft(aircraftId);
        terminalAccessPolicy.requireSameGroup(caller,
                String.valueOf(aircraft.get("exercise_group_id")));
        aircraft.put("flightPlans", flightPlanService.listVersions(aircraftId));
        return withDtoAliases(aircraft);
    }

    /**
     * v2 DTO 别名（详细设计 9.1 camelCase 契约）：
     * 数据库行仍以 snake_case 返回，同时提供验收契约使用的 camelCase 键。
     */
    private static Map<String, Object> withDtoAliases(Map<String, Object> row) {
        Map<String, Object> body = new LinkedHashMap<>(row);
        putAlias(body, "callsign", "callsign");
        putAlias(body, "squawkCode", "current_squawk");
        putAlias(body, "ssrMode", "ssr_mode");
        putAlias(body, "flightPhase", "flight_phase");
        putAlias(body, "controlStatus", "control_status");
        putAlias(body, "icao24", "icao24");
        putAlias(body, "plannedSquawk", "planned_squawk");
        putAlias(body, "identActive", "transponder_ident_active");
        putAlias(body, "identExpiresAtSeconds", "transponder_ident_expires_at");
        putAlias(body, "isFake", "is_fake");
        putAlias(body, "responsibleTerminalId", "assigned_terminal_id");
        putAlias(body, "altitudeFtMsl", "altitude_feet");
        putAlias(body, "speedKnots", "speed_knots");
        putAlias(body, "headingDegrees", "heading_degrees");
        return body;
    }

    private static void putAlias(Map<String, Object> body, String alias, String source) {
        if (body.containsKey(source) && !body.containsKey(alias)) {
            body.put(alias, body.get(source));
        }
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listByGroup(
            org.bluesky.training.common.CallerContext caller, String groupId) {
        terminalAccessPolicy.requireSameGroup(caller, groupId);
        requireGroupExists(groupId);
        return aircraftMapper.listByGroup(groupId);
    }

    /** 只修改 PLANNED/CREATE_FAILED 计划；ACTIVE 航空器必须走指令网关（详细设计 9.2）。 */
    @Transactional
    public Map<String, Object> patchPlannedFlightPlan(
            org.bluesky.training.common.CallerContext caller, String aircraftId,
            long expectedRevision, Map<String, Object> payload) {
        Map<String, Object> aircraft = requireAircraft(aircraftId);
        terminalAccessPolicy.requireSameGroup(caller,
                String.valueOf(aircraft.get("exercise_group_id")));
        Map<String, Object> assignment = aircraftMapper.findCurrentAssignment(aircraftId);
        terminalAccessPolicy.requireResponsibleTerminal(caller,
                assignment == null ? null : String.valueOf(assignment.get("terminal_id")));
        String lifecycle = String.valueOf(aircraft.get("lifecycle"));
        if ("ACTIVE".equals(lifecycle)) {
            throw new V2DomainException("TRAINING_STATE_INVALID", 409,
                    "活动航空器的航路修改必须走指令网关", Arrays.asList("lifecycle"));
        }
        if (!"PLANNED".equals(lifecycle) && !"CREATE_FAILED".equals(lifecycle)) {
            throw new V2DomainException("TRAINING_STATE_INVALID", 409,
                    "当前生命周期不允许修改计划: " + lifecycle, Arrays.asList("lifecycle"));
        }
        BigDecimal targetAppearance = null;
        if (payload.containsKey("targetAppearanceSimulationTimeSeconds")) {
            double target = number(payload.get("targetAppearanceSimulationTimeSeconds"),
                    "targetAppearanceSimulationTimeSeconds");
            requireTargetAppearanceNotInPast(
                    String.valueOf(aircraft.get("exercise_group_id")), target);
            targetAppearance = BigDecimal.valueOf(target);
        }
        long actualRevision = ((Number) aircraft.get("revision")).longValue();
        if (actualRevision != expectedRevision
                || aircraftMapper.updatePlanRevision(aircraftId, expectedRevision,
                        targetAppearance) != 1) {
            throw new V2DomainException("REVISION_CONFLICT", 409,
                    "航空器修订号已变化", Arrays.asList("If-Match"));
        }
        Map<String, Object> plan = flightPlanService.createVersion(aircraftId,
                String.valueOf(aircraft.get("origin")),
                String.valueOf(aircraft.get("destination")),
                String.valueOf(aircraft.get("planned_squawk")),
                String.valueOf(aircraft.get("ssr_mode")),
                intOrNull(payload.get("cruiseAltitudeFtMsl"), "cruiseAltitudeFtMsl"),
                intOrNull(payload.get("cruiseIndicatedAirspeedKt"),
                        "cruiseIndicatedAirspeedKt"),
                routeOf(payload.get("route")));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", aircraftId);
        body.put("flightPlan", plan);
        body.put("revision", expectedRevision + 1);
        if (targetAppearance != null) {
            body.put("targetAppearanceSimulationTimeSeconds", targetAppearance);
        }
        outboxService.enqueueBusinessEvent(OutboxEventRow.businessEvent(
                UUID.randomUUID().toString(), String.valueOf(aircraft.get("exercise_group_id")),
                "aircraft.flight-plan.updated", jsonOf("aircraftId", aircraftId,
                        "planVersion", plan.get("planVersion"),
                        "revision", expectedRevision + 1)));
        return body;
    }

    /** 取消未出现计划：无 BlueSky 实体，同事务直达 DELETED（详细设计 2.2 §5.2.9）。 */
    @Transactional
    public Map<String, Object> cancelPlannedAircraft(
            org.bluesky.training.common.CallerContext caller, String aircraftId) {
        Map<String, Object> aircraft = requireAircraft(aircraftId);
        terminalAccessPolicy.requireSameGroup(caller,
                String.valueOf(aircraft.get("exercise_group_id")));
        Map<String, Object> assignment = aircraftMapper.findCurrentAssignment(aircraftId);
        terminalAccessPolicy.requireResponsibleTerminal(caller,
                assignment == null ? null : String.valueOf(assignment.get("terminal_id")));
        String lifecycle = String.valueOf(aircraft.get("lifecycle"));
        if (!"PLANNED".equals(lifecycle) && !"CREATE_FAILED".equals(lifecycle)) {
            throw new V2DomainException("TRAINING_STATE_INVALID", 409,
                    "只有未出现航空器可以本地取消，当前: " + lifecycle, Arrays.asList("lifecycle"));
        }
        if (aircraftMapper.markDeleted(aircraftId, lifecycle) != 1) {
            throw new V2DomainException("REVISION_CONFLICT", 409,
                    "航空器状态已被并发修改", Arrays.asList("revision"));
        }
        if (assignment != null) {
            aircraftMapper.endAssignment(String.valueOf(assignment.get("id")));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", aircraftId);
        body.put("lifecycle", "DELETED");
        body.put("revision", ((Number) aircraft.get("revision")).longValue() + 1);
        outboxService.enqueueBusinessEvent(OutboxEventRow.businessEvent(
                UUID.randomUUID().toString(), String.valueOf(aircraft.get("exercise_group_id")),
                "aircraft.lifecycle.changed", jsonOf("aircraftId", aircraftId,
                        "lifecycle", "DELETED", "revision", body.get("revision"))));
        return body;
    }

    private void requireGroupExists(String groupId) {
        if (groupId == null || aircraftMapper.findGroupStateAndTime(groupId) == null) {
            throw new V2DomainException("TERMINAL_NOT_FOUND", 404, "训练组不存在: " + groupId);
        }
    }

    private void requireGroupStateAllowsPlan(String groupId) {
        String state = String.valueOf(aircraftMapper.findGroupStateAndTime(groupId).get("state"));
        if (!Arrays.asList("READY", "RUNNING", "PAUSED").contains(state)) {
            throw new V2DomainException("TRAINING_STATE_INVALID", 409,
                    "当前训练状态不允许创建计划: " + state, Arrays.asList("groupState"));
        }
    }

    private void requireTargetAppearanceNotInPast(String groupId, double targetAppearance) {
        if (!Double.isFinite(targetAppearance) || targetAppearance < 0) {
            throw new V2DomainException("INVALID_INSTRUCTION", 400,
                    "目标出现仿真时间必须是有限非负数",
                    Arrays.asList("targetAppearanceSimulationTimeSeconds"));
        }
        Number current = (Number) aircraftMapper.findGroupStateAndTime(groupId)
                .get("simulation_time_seconds");
        if (current != null && targetAppearance < current.doubleValue()) {
            throw new V2DomainException("INVALID_INSTRUCTION", 400,
                    "目标出现仿真时间不得早于当前仿真时间",
                    Arrays.asList("targetAppearanceSimulationTimeSeconds"));
        }
    }

    private void requireEnabledTerminalInGroup(String groupId, String terminalId) {
        if (terminalId == null || aircraftMapper.countEnabledTerminalInGroup(groupId, terminalId) != 1) {
            throw new V2DomainException("TERMINAL_NOT_FOUND", 404,
                    "初始责任终端不存在、未启用或不属于训练组: " + terminalId,
                    Arrays.asList("assignedTerminalId"));
        }
    }

    private Map<String, Object> requireAircraft(String aircraftId) {
        Map<String, Object> aircraft = aircraftId == null ? null : aircraftMapper.findById(aircraftId);
        if (aircraft == null) {
            throw new V2DomainException("TERMINAL_NOT_FOUND", 404, "航空器不存在: " + aircraftId);
        }
        return aircraft;
    }

    private static Map<String, Object> requireSection(Map<String, Object> request, String key) {
        Map<String, Object> section = section(request, key);
        if (section == null) {
            throw new V2DomainException("INVALID_INSTRUCTION", 400,
                    "缺少 " + key + " 段", Arrays.asList(key));
        }
        return section;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> section(Map<String, Object> request, String key) {
        if (request == null) {
            return null;
        }
        Object value = request.get(key);
        return value instanceof Map ? (Map<String, Object>) value : null;
    }

    @SuppressWarnings("unchecked")
    private static List<String> routeOf(Object routeObject) {
        if (routeObject instanceof List) {
            List<String> points = new ArrayList<>();
            for (Object point : (List<Object>) routeObject) {
                if (!(point instanceof String)) {
                    throw new V2DomainException("INVALID_INSTRUCTION", 400,
                            "route 中每个航路点必须是字符串", Arrays.asList("route"));
                }
                points.add((String) point);
            }
            return points;
        }
        return Collections.emptyList();
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private static String upper(Object value) {
        String text = text(value);
        return text == null ? null : text.toUpperCase();
    }

    private static double number(Object value, String field) {
        if (!(value instanceof Number)) {
            throw new V2DomainException("INVALID_INSTRUCTION", 400,
                    field + " 必须是数值", Arrays.asList(field));
        }
        double number = ((Number) value).doubleValue();
        if (!Double.isFinite(number)) {
            throw new V2DomainException("INVALID_INSTRUCTION", 400,
                    field + " 必须是有限数值", Arrays.asList(field));
        }
        return number;
    }

    private static Integer intOrNull(Object value, String field) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof Number)) {
            throw new V2DomainException("INVALID_INSTRUCTION", 400,
                    field + " 必须是整数", Arrays.asList(field));
        }
        double number = ((Number) value).doubleValue();
        if (!Double.isFinite(number) || number != Math.rint(number)
                || number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) {
            throw new V2DomainException("INVALID_INSTRUCTION", 400,
                    field + " 必须是有效整数", Arrays.asList(field));
        }
        return Integer.valueOf((int) number);
    }

    private static String requiredCode(Object value, String field, String pattern) {
        String normalized = upper(value);
        if (normalized == null || !normalized.matches(pattern)) {
            throw new V2DomainException("INVALID_INSTRUCTION", 400,
                    field + " 缺失或格式非法", Arrays.asList(field));
        }
        return normalized;
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
