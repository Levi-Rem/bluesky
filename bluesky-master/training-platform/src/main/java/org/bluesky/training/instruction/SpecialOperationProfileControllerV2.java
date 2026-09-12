package org.bluesky.training.instruction;

import org.bluesky.training.common.CallerContextResolver;
import org.bluesky.training.common.V2Api;
import org.bluesky.training.common.V2DomainException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** P14 v2 特情 profile 接口（详细设计 2.2 §7.8/§9.2）：草稿→发布不可变。 */
@V2Api
@RestController
@RequestMapping("/api/v2/special-operation-profiles")
public class SpecialOperationProfileControllerV2 {

    private final CallerContextResolver resolver;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    @org.springframework.beans.factory.annotation.Autowired private org.bluesky.training.common.IdempotentHttpExecutor idempotency;

    public SpecialOperationProfileControllerV2(CallerContextResolver resolver,
                                               org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.resolver = resolver;
        this.jdbc = jdbc;
    }

    @GetMapping
    public Map<String, Object> list(HttpServletRequest request) {
        resolver.resolveService(request);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", jdbc.queryForList(
                "SELECT id, operation_type, mode_code, status, affected_channels, adapter_action, "
                        + "duration_seconds, override_policy, recovery_policy, checksum, revision "
                        + "FROM special_operation_profile ORDER BY created_at DESC"));
        return body;
    }

    @PostMapping
    public ResponseEntity<Object> create(
            @RequestBody Map<String, Object> payload,
            HttpServletRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        org.bluesky.training.common.CallerContext caller=resolver.resolveService(request);
        return idempotency.execute(caller,"POST","/api/v2/special-operation-profiles",key,payload,201,()->createBody(payload));
    }

    private Map<String,Object> createBody(Map<String,Object> payload) {
        String operationType = upper(payload.get("operationType"));
        if (!Arrays.asList("ID", "DECOMP").contains(operationType)) {
            throw new V2DomainException("INVALID_INSTRUCTION", 400,
                    "operationType 只能是 ID/DECOMP", Arrays.asList("operationType"));
        }
        String adapterAction = upper(payload.get("adapterAction"));
        if (adapterAction == null) {
            adapterAction = "DECOMP".equals(operationType)
                    ? "DECOMPRESSION_APPLY" : "SPECIAL_MARK_APPLY";
        }
        SpecialProfileSchemaRegistry.validateAdapterAction(adapterAction);
        String overridePolicy = upper(payload.get("overridePolicy")) == null
                ? "ALL_OR_NOTHING" : upper(payload.get("overridePolicy"));
        SpecialProfileSchemaRegistry.validateOverridePolicy(overridePolicy);
        String recoveryPolicy = upper(payload.get("recoveryPolicy")) == null
                ? "RESTORE_PREVIOUS_GUIDANCE" : upper(payload.get("recoveryPolicy"));
        SpecialProfileSchemaRegistry.validateRecoveryPolicy(recoveryPolicy);
        int duration = integer(payload.getOrDefault("durationSeconds", 300), "durationSeconds", 1, 3600);
        SpecialProfileSchemaRegistry.validateDuration(duration);

        String id = UUID.randomUUID().toString();
        String affected = String.join(",", (java.util.List<String>)
                (payload.get("affectedChannels") instanceof java.util.List
                        ? payload.get("affectedChannels") : Arrays.asList("VERTICAL")));
        if(affected.isEmpty() || !Arrays.asList("LATERAL","VERTICAL","SPEED").containsAll(Arrays.asList(affected.split(","))))throw new V2DomainException("INVALID_INSTRUCTION",400,"affectedChannels 非法");
        String schema=String.valueOf(payload.getOrDefault("parametersSchemaVersion","special-profile/1"));
        Map<String,Object> parameters;
        try {
            Object raw=payload.getOrDefault("parametersJson","{}");
            parameters=raw instanceof Map?(Map<String,Object>)raw:new com.fasterxml.jackson.databind.ObjectMapper().readValue(String.valueOf(raw),Map.class);
        } catch(java.io.IOException invalid){throw new V2DomainException("INVALID_INSTRUCTION",400,"parametersJson 必须是 JSON 对象");}
        SpecialProfileSchemaRegistry.validateSchemaVersion(schema,parameters);
        String parametersJson;
        try{parametersJson=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(parameters);}catch(java.io.IOException e){throw new IllegalStateException(e);}
        jdbc.update("INSERT INTO special_operation_profile (id, operation_type, mode_code, "
                        + "status, affected_channels, adapter_action, duration_seconds, "
                        + "override_policy, recovery_policy, parameters_schema_version, "
                        + "parameters_json, target_altitude_ft_msl, vertical_rate_fpm, target_indicated_airspeed_kt) VALUES (?, ?, ?, 'DRAFT', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, operationType, upper(payload.get("modeCode")) == null
                        ? ("DECOMP".equals(operationType) ? "N" : "DEFAULT")
                        : upper(payload.get("modeCode")),
                affected, adapterAction, duration, overridePolicy, recoveryPolicy,
                schema, parametersJson,
                integer(payload.get("targetAltitudeFtMsl"), "targetAltitudeFtMsl", 0, 60000),
                integer(payload.get("verticalRateFpm"), "verticalRateFpm", -10000, 10000),
                integer(payload.get("targetIndicatedAirspeedKt"), "targetIndicatedAirspeedKt", 0, 600));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", id);
        body.put("status", "DRAFT");
        body.put("revision", 1L);
        return body;
    }

    @PostMapping("/{profileId}/actions/publish")
    public Map<String, Object> publish(@PathVariable("profileId") String profileId,
                                       @RequestBody Map<String, Object> payload,
                                       HttpServletRequest request,
                                       @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        resolver.resolveService(request);
        String status = jdbc.queryForObject(
                "SELECT status FROM special_operation_profile WHERE id = ?", String.class, profileId);
        if (status == null) {
            throw new V2DomainException("TERMINAL_NOT_FOUND", 404, "profile 不存在: " + profileId);
        }
        String next = SpecialProfileSchemaRegistry.transition(status, "PUBLISH");
        // 规范化后计算 checksum（客户端不得自行指定，详细设计 9.6）
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT operation_type, mode_code, adapter_action, duration_seconds, affected_channels, "
                        + "target_altitude_ft_msl, vertical_rate_fpm, target_indicated_airspeed_kt, parameters_schema_version, parameters_json, override_policy, recovery_policy FROM special_operation_profile WHERE id = ?",
                profileId);
        String checksum = SpecialProfileSchemaRegistry.canonicalizeAndChecksum(row);
        jdbc.update("UPDATE special_operation_profile SET status = ?, checksum = ?, "
                        + "effective_from = CURRENT_TIMESTAMP(3), published_by = ?, "
                        + "revision = revision + 1 WHERE id = ?",
                next, checksum, resolver.resolveService(request).callerId(), profileId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", profileId);
        body.put("status", next);
        body.put("checksum", checksum);
        return body;
    }

    @PostMapping("/{profileId}/actions/retire")
    public Map<String, Object> retire(@PathVariable("profileId") String profileId,
                                      HttpServletRequest request,
                                      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        resolver.resolveService(request);
        String status = jdbc.queryForObject(
                "SELECT status FROM special_operation_profile WHERE id = ?", String.class, profileId);
        String next = SpecialProfileSchemaRegistry.transition(status, "RETIRE");
        jdbc.update("UPDATE special_operation_profile SET status = ?, revision = revision + 1 "
                + "WHERE id = ?", next, profileId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", profileId);
        body.put("status", next);
        return body;
    }

    private static Integer integer(Object value, String field, int min, int max) {
        if (value == null) return null;
        if (value instanceof Number) {
            double number = ((Number) value).doubleValue();
            if (Double.isFinite(number) && number == Math.rint(number) && number >= min && number <= max)
                return (int) number;
        }
        throw new V2DomainException("INVALID_INSTRUCTION", 400, field + " 必须是范围内的整数", Arrays.asList(field));
    }

    private static String upper(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim().toUpperCase();
        return text.isEmpty() ? null : text;
    }
}
