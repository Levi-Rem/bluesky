package org.bluesky.training.faketarget;

import org.bluesky.training.common.CallerContextResolver;
import org.bluesky.training.common.V2Api;
import org.bluesky.training.common.V2DomainException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** P16 v2 假目标接口（详细设计 2.2 §5.6/§9.6）：两类目标、修订校验、停止不可重启。 */
@V2Api
@RestController
@RequestMapping("/api/v2")
public class FakeTargetControllerV2 {

    private final CallerContextResolver resolver;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    @org.springframework.beans.factory.annotation.Autowired private org.bluesky.training.common.IdempotentHttpExecutor idempotency;
    @org.springframework.beans.factory.annotation.Autowired private org.bluesky.training.common.TerminalAccessPolicy access;
    @org.springframework.beans.factory.annotation.Autowired private org.bluesky.training.aircraft.AircraftApplicationService aircraft;
    @org.springframework.beans.factory.annotation.Autowired private FakeTargetRuntime runtime;

    public FakeTargetControllerV2(CallerContextResolver resolver,
                                  org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.resolver = resolver;
        this.jdbc = jdbc;
    }

    @PostMapping("/exercise-groups/{groupId}/fake-targets")
    public ResponseEntity<Object> create(
            @PathVariable("groupId") String groupId,
            @RequestBody Map<String, Object> payload,
            HttpServletRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        org.bluesky.training.common.CallerContext caller = resolver.resolveTerminal(request);
        access.requireSameGroup(caller,groupId);
        return idempotency.execute(caller,"POST","/api/v2/exercise-groups/"+groupId+"/fake-targets",key,payload,201,()->createBody(caller,groupId,payload));
    }

    private Map<String,Object> createBody(org.bluesky.training.common.CallerContext caller,String groupId,Map<String,Object> payload) {
        List<Map<String,Object>> groups=jdbc.queryForList("SELECT state,simulation_time_seconds FROM exercise_group WHERE id=? FOR UPDATE",groupId);
        if(groups.isEmpty())throw new V2DomainException("TERMINAL_NOT_FOUND",404,"训练组不存在");
        access.requireGroupStateAllows(String.valueOf(groups.get(0).get("state")),Arrays.asList("READY","RUNNING","PAUSED"));
        String kind = upper(payload.get("targetKind"));
        if (!Arrays.asList("RADAR_SYNTHETIC", "SIMULATED_AIRCRAFT").contains(kind)) {
            throw new V2DomainException("INVALID_INSTRUCTION", 400,
                    "targetKind 必须是 RADAR_SYNTHETIC/SIMULATED_AIRCRAFT",
                    Arrays.asList("targetKind"));
        }
        String callsign = upper(payload.get("callsign"));
        if (callsign == null || !callsign.matches("[A-Z0-9]{2,12}")) {
            throw new V2DomainException("INVALID_INSTRUCTION", 400,
                    "callsign 必须是 2–12 位大写字母/数字", Arrays.asList("callsign"));
        }
        Double start = num(payload.get("startSimulationTimeSeconds"));
        Double expire = num(payload.get("expireSimulationTimeSeconds"));
        if (start == null || expire == null || expire <= start) {
            throw new V2DomainException("INVALID_INSTRUCTION", 400,
                    "必须提供 start/expire 仿真时间且 expire > start",
                    Arrays.asList("expireSimulationTimeSeconds"));
        }
        if(!Double.isFinite(start) || !Double.isFinite(expire) || start<((Number)groups.get(0).get("simulation_time_seconds")).doubleValue())throw new V2DomainException("INVALID_INSTRUCTION",400,"假目标出现时刻不能早于当前仿真时间");
        if("RADAR_SYNTHETIC".equals(kind)) {
        for(String field:Arrays.asList("latitudeDeg","longitudeDeg","trueHeadingDeg","groundSpeedKt","altitudeFtMsl"))if(num(payload.get(field))==null || !Double.isFinite(num(payload.get(field))))throw new V2DomainException("INVALID_INSTRUCTION",400,"缺少有效参数: "+field);
        if(Math.abs(num(payload.get("latitudeDeg")))>90 || Math.abs(num(payload.get("longitudeDeg")))>180 || num(payload.get("groundSpeedKt"))<0 || num(payload.get("trueHeadingDeg"))<0 || num(payload.get("trueHeadingDeg"))>=360)throw new V2DomainException("INVALID_INSTRUCTION",400,"假目标坐标、速度或航向超出范围");
        }
        String aircraftId=null;
        if("SIMULATED_AIRCRAFT".equals(kind)) {
            if(!(payload.get("aircraftPlan") instanceof Map))throw new V2DomainException("INVALID_INSTRUCTION",400,"SIMULATED_AIRCRAFT 必须提供完整 aircraftPlan");
            Map<String,Object> plan=new LinkedHashMap<>((Map<String,Object>)payload.get("aircraftPlan"));
            Map<String,Object> spec=new LinkedHashMap<>((Map<String,Object>)plan.getOrDefault("aircraft",java.util.Collections.emptyMap()));
            spec.put("isFake",true);spec.put("callsign",callsign);plan.put("aircraft",spec);plan.put("targetAppearanceSimulationTimeSeconds",start);
            aircraftId=String.valueOf(aircraft.createPlan(caller,groupId,plan).get("id"));
        }
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO fake_target (id, exercise_group_id, target_kind, callsign, "
                        + "squawk, state, start_simulation_seconds, expire_simulation_seconds, "
                        + "latitude_deg, longitude_deg, true_heading_deg, ground_speed_kt, "
                        + "altitude_ft_msl, created_terminal_id) VALUES (?, ?, ?, ?, ?, "
                        + "'SCHEDULED', ?, ?, ?, ?, ?, ?, ?, ?)",
                id, groupId, kind, callsign, text(payload.get("squawk")),
                BigDecimal.valueOf(start), BigDecimal.valueOf(expire),
                dec(payload.get("latitudeDeg")), dec(payload.get("longitudeDeg")),
                dec(payload.get("trueHeadingDeg")),
                (Number) payload.get("groundSpeedKt") instanceof Number
                        ? ((Number) payload.get("groundSpeedKt")).intValue() : null,
                (Number) payload.get("altitudeFtMsl") instanceof Number
                        ? ((Number) payload.get("altitudeFtMsl")).intValue() : null,
                caller.terminalId());
        jdbc.update("UPDATE fake_target SET is_fake_aircraft_id=?,last_simulation_seconds=? WHERE id=?",aircraftId,start,id);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", id);
        body.put("targetKind", kind);
        body.put("callsign", callsign);
        body.put("state", "SCHEDULED");
        body.put("revision", 1L);
        body.put("aircraftId",aircraftId);
        return body;
    }

    @GetMapping("/exercise-groups/{groupId}/fake-targets")
    public Map<String, Object> list(@PathVariable("groupId") String groupId) {
        access.requireSameGroup(resolver.resolveCurrent(),groupId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", jdbc.queryForList(
                "SELECT id, target_kind, callsign, squawk, state, "
                        + "start_simulation_seconds, expire_simulation_seconds, "
                        + "latitude_deg, longitude_deg, true_heading_deg, ground_speed_kt, "
                        + "altitude_ft_msl, revision FROM fake_target "
                        + "WHERE exercise_group_id = ? ORDER BY created_at DESC", groupId));
        return body;
    }

    @GetMapping("/fake-targets/{targetId}")
    public Map<String, Object> get(@PathVariable("targetId") String targetId) {
        Map<String,Object> target=requireTarget(targetId);
        access.requireSameGroup(resolver.resolveCurrent(),String.valueOf(target.get("exercise_group_id")));
        return target;
    }

    /** PATCH 只允许修改尚未到期的合成目标航向/地速/高度/到期时间（详细设计 9.6）。 */
    @PatchMapping("/fake-targets/{targetId}")
    @org.springframework.transaction.annotation.Transactional
    public Map<String, Object> patch(@PathVariable("targetId") String targetId,
                                     @RequestBody Map<String, Object> payload,
                                     HttpServletRequest request,
                                     @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        requireTerminalOf(resolver, request, targetId);
        Map<String, Object> target = requireTarget(targetId);
        runtime.advance(targetId);
        target = requireTarget(targetId);
        if(!Arrays.asList("SCHEDULED","ACTIVE","STOPPED").contains(target.get("state")))throw new V2DomainException("TRAINING_STATE_INVALID",409,"假目标已结束");
        for(String field:Arrays.asList("trueHeadingDeg","groundSpeedKt","altitudeFtMsl","expireSimulationTimeSeconds")) {
            if(payload.containsKey(field) && (!(payload.get(field) instanceof Number) || !Double.isFinite(((Number)payload.get(field)).doubleValue())))
                throw new V2DomainException("INVALID_INSTRUCTION",400,field+" 必须为有限数值");
        }
        if(payload.get("trueHeadingDeg")!=null && (num(payload.get("trueHeadingDeg"))<0 || num(payload.get("trueHeadingDeg"))>=360) ||
                payload.get("groundSpeedKt")!=null && num(payload.get("groundSpeedKt"))<0)
            throw new V2DomainException("INVALID_INSTRUCTION",400,"航向或速度超出范围");
        if (!"RADAR_SYNTHETIC".equals(target.get("target_kind"))) {
            throw new V2DomainException("INVALID_INSTRUCTION", 400,
                    "BlueSky 假航空器必须通过普通指令修改", Arrays.asList("targetKind"));
        }
        Long expected = org.bluesky.training.common.RevisionHeaders.requireIfMatch(ifMatch);
        Long actual = ((Number) target.get("revision")).longValue();
        if (expected != null && !expected.equals(actual)) {
            throw new V2DomainException("REVISION_CONFLICT", 409, "目标修订号已变化",
                    Arrays.asList("targetRevision"));
        }
        jdbc.update("UPDATE fake_target SET true_heading_deg = COALESCE(?, true_heading_deg), "
                        + "ground_speed_kt = COALESCE(?, ground_speed_kt), "
                        + "altitude_ft_msl = COALESCE(?, altitude_ft_msl), expire_simulation_seconds = COALESCE(?,expire_simulation_seconds), revision = revision + 1 "
                        + "WHERE id = ? AND revision = ?",
                dec(payload.get("trueHeadingDeg")),
                (Number) payload.get("groundSpeedKt") instanceof Number
                        ? ((Number) payload.get("groundSpeedKt")).intValue() : null,
                (Number) payload.get("altitudeFtMsl") instanceof Number
                        ? ((Number) payload.get("altitudeFtMsl")).intValue() : null,
                dec(payload.get("expireSimulationTimeSeconds")), targetId, expected);
        return requireTarget(targetId);
    }

    @PostMapping("/fake-targets/{targetId}/actions/stop")
    @org.springframework.transaction.annotation.Transactional
    public Map<String, Object> stop(@PathVariable("targetId") String targetId,
                                    HttpServletRequest request,
                                    @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        requireTerminalOf(resolver, request, targetId);
        Map<String, Object> target = requireTarget(targetId);
        String state = String.valueOf(target.get("state"));
        if("SIMULATED_AIRCRAFT".equals(target.get("target_kind")))throw new V2DomainException("INVALID_INSTRUCTION",400,"假航空器通过飞行指令或删除操作控制");
        if("STOPPED".equals(state))return target;
        runtime.advance(targetId);
        target=requireTarget(targetId);state=String.valueOf(target.get("state"));
        // SCHEDULED 尚未激活：先激活再停止（详细设计 5.6 状态机唯一转换）
        if ("SCHEDULED".equals(state)) {
            state = FakeTargetDomain.transition(state, "ACTIVATE");
            jdbc.update("UPDATE fake_target SET state = ? WHERE id = ?", state, targetId);
        }
        String next = FakeTargetDomain.transition(state, "STOP");
        jdbc.update("UPDATE fake_target SET state = ?, revision = revision + 1 WHERE id = ?",
                next, targetId);
        return requireTarget(targetId);
    }

    @DeleteMapping("/fake-targets/{targetId}")
    @org.springframework.transaction.annotation.Transactional
    public Map<String, Object> delete(@PathVariable("targetId") String targetId,
                                      HttpServletRequest request,
                                      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        requireTerminalOf(resolver, request, targetId);
        Map<String, Object> target = requireTarget(targetId);
        String state = String.valueOf(target.get("state"));
        if("SIMULATED_AIRCRAFT".equals(target.get("target_kind"))) {runtime.requestDelete(targetId);return requireTarget(targetId);}
        String next = FakeTargetDomain.transition(state, "REQUEST_DELETE");
        jdbc.update("UPDATE fake_target SET state = ?, revision = revision + 1 WHERE id = ?",
                next, targetId);
        // 合成目标无 BlueSky 实体：直接确认删除；假航空器实际 Saga 接线后替换
        if ("DELETE_REQUESTED".equals(next)
                && "RADAR_SYNTHETIC".equals(target.get("target_kind"))) {
            next = FakeTargetDomain.transition(next, "CONFIRM_DELETE");
            jdbc.update("UPDATE fake_target SET state = ? WHERE id = ?", next, targetId);
        }
        return requireTarget(targetId);
    }

    private Map<String, Object> requireTarget(String targetId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, exercise_group_id, target_kind, callsign, squawk, state, "
                        + "start_simulation_seconds, expire_simulation_seconds, latitude_deg, "
                        + "longitude_deg, true_heading_deg, ground_speed_kt, altitude_ft_msl, "
                        + "created_terminal_id, revision FROM fake_target WHERE id = ?", targetId);
        if (rows.isEmpty()) {
            throw new V2DomainException("TERMINAL_NOT_FOUND", 404,
                    "假目标不存在: " + targetId);
        }
        return rows.get(0);
    }

    private void requireTerminalOf(CallerContextResolver resolver, HttpServletRequest request,
                                   String targetId) {
        org.bluesky.training.common.CallerContext caller = resolver.resolveTerminal(request);
        Map<String, Object> target = requireTarget(targetId);
        if (!caller.terminalId().equals(String.valueOf(target.get("created_terminal_id")))) {
            throw new V2DomainException("AIRCRAFT_NOT_ASSIGNED", 403,
                    "只有创建终端可以操作该假目标", Arrays.asList("terminalId"));
        }
    }

    private static Long revision(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static BigDecimal dec(Object value) {
        return value instanceof Number
                ? BigDecimal.valueOf(((Number) value).doubleValue()) : null;
    }

    private static Double num(Object value) {
        return value instanceof Number ? ((Number) value).doubleValue() : null;
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
}
