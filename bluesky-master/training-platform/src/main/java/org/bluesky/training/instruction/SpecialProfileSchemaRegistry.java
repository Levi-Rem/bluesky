package org.bluesky.training.instruction;

import org.bluesky.training.common.V2DomainException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P14：特情 profile 状态机与 Schema 校验（详细设计 2.2 §7.8）。
 * ID/DECOMP 航空动作只来自业务方签署的发布 profile，代码不得猜测；
 * 服务端规范化后计算 checksum，客户端不得自行指定。
 */
public class SpecialProfileSchemaRegistry {

    public static final List<String> ADAPTER_ACTIONS = Arrays.asList(
            "SPECIAL_MARK_APPLY", "DECOMPRESSION_APPLY", "SPECIAL_OPERATION_CLEAR");
    public static final List<String> OVERRIDE_POLICIES = Arrays.asList(
            "ALL_OR_NOTHING", "PARTIAL");
    public static final List<String> RECOVERY_POLICIES = Arrays.asList(
            "RESTORE_PREVIOUS_GUIDANCE", "RESTORE_MANAGED_TARGET", "HOLD_RESULT");

    /** 通道非空且都是合法通道名（详细设计 7.8.3）。 */
    public static List<String> validateSchemaVersion(String schemaVersion,
                                                     Map<String, Object> parameters) {
        if (schemaVersion == null || !schemaVersion.matches("special-profile/\\d+")) {
            throw new V2DomainException("INVALID_INSTRUCTION", 400,
                    "parametersSchemaVersion 必须形如 special-profile/N",
                    Arrays.asList("parametersSchemaVersion"));
        }
        if (parameters == null)throw new V2DomainException("INVALID_INSTRUCTION",400,"profile 参数必须是 JSON 对象");
        if (!parameters.isEmpty()) {
            // 参数须能与声明 Schema 对应：P14 阶段要求值为 JSON 基本类型
            for (Map.Entry<String, Object> entry : parameters.entrySet()) {
                Object value = entry.getValue();
                if (!(value == null || value instanceof String || value instanceof Number
                        || value instanceof Boolean)) {
                    throw new V2DomainException("INVALID_INSTRUCTION", 400,
                            "profile 参数必须是基本类型: " + entry.getKey(),
                            Arrays.asList("parametersJson." + entry.getKey()));
                }
            }
        }
        return new ArrayList<>();
    }

    public static String validateAdapterAction(String adapterAction) {
        if (!ADAPTER_ACTIONS.contains(adapterAction)) {
            throw new V2DomainException("INVALID_INSTRUCTION", 400,
                    "adapterAction 只允许 " + ADAPTER_ACTIONS + "，不允许 BlueSky 原生命令文本: "
                            + adapterAction, Arrays.asList("adapterAction"));
        }
        return adapterAction;
    }

    public static String validateOverridePolicy(String overridePolicy) {
        if (!OVERRIDE_POLICIES.contains(overridePolicy)) {
            throw new V2DomainException("INVALID_INSTRUCTION", 400,
                    "overridePolicy 只允许 ALL_OR_NOTHING/PARTIAL: " + overridePolicy,
                    Arrays.asList("overridePolicy"));
        }
        return overridePolicy;
    }

    public static String validateRecoveryPolicy(String recoveryPolicy) {
        if (!RECOVERY_POLICIES.contains(recoveryPolicy)) {
            throw new V2DomainException("INVALID_INSTRUCTION", 400,
                    "recoveryPolicy 只允许 " + RECOVERY_POLICIES + ": " + recoveryPolicy,
                    Arrays.asList("recoveryPolicy"));
        }
        return recoveryPolicy;
    }

    /** 持续时间 1–3600 秒（详细设计 7.8 固定契约）。 */
    public static int validateDuration(int durationSeconds) {
        if (durationSeconds < 1 || durationSeconds > 3600) {
            throw new V2DomainException("INVALID_INSTRUCTION", 400,
                    "durationSeconds 范围 1–3600: " + durationSeconds,
                    Arrays.asList("durationSeconds"));
        }
        return durationSeconds;
    }

    /** DECOMP 必须分别发布 N、S profile 并提供 CLR 清除映射（详细设计 7.8 固定契约）。 */
    public static List<String> requiredModes(String operationType) {
        if ("DECOMP".equals(operationType)) {
            return Arrays.asList("N", "S", "CLR");
        }
        if ("ID".equals(operationType)) {
            return Arrays.asList("DEFAULT");
        }
        throw new V2DomainException("INVALID_INSTRUCTION", 400,
                "operationType 只能是 ID/DECOMP: " + operationType,
                Arrays.asList("operationType"));
    }

    /** 草稿→发布状态机：发布不可变；已发布 PUT 返回 409 PROFILE_IMMUTABLE（由服务层映射）。 */
    public static String transition(String current, String event) {
        Map<String, String> legal = new LinkedHashMap<>();
        legal.put("DRAFT+PUBLISH", "PUBLISHED");
        legal.put("DRAFT+UPDATE", "DRAFT");
        legal.put("PUBLISHED+RETIRE", "RETIRED");
        String target = legal.get(current + "+" + event);
        if (target == null) {
            throw new V2DomainException("PROFILE_IMMUTABLE", 409,
                    "profile 状态转换非法（已发布/已停用不可修改）: " + current + "+" + event,
                    Arrays.asList("status"));
        }
        return target;
    }

    /** 规范化（键排序、去空白）后计算 SHA-256（详细设计 9.6）。 */
    public static String canonicalizeAndChecksum(Map<String, Object> profile) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper json=new com.fasterxml.jackson.databind.ObjectMapper().enable(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
            Map<String,Object> canonical=new java.util.TreeMap<>(profile);
            Object parameters=canonical.get("parameters_json");
            if(parameters instanceof String)canonical.put("parameters_json",json.readValue((String)parameters,Map.class));
            return org.bluesky.training.reference.ReferenceSnapshotStore.sha256OfText(json.writeValueAsString(canonical));
        } catch(java.io.IOException e){throw new V2DomainException("INVALID_INSTRUCTION",400,"profile 参数无法规范化");}
    }
}
