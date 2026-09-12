package org.bluesky.training.contract;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * P00：检查详细设计 2.2 中固定枚举与机器可读生成物的一致性。
 * 本类中的常量集合逐字转录自《模拟飞行员工作台第二版详细设计》2.2，
 * 是 Java 侧唯一允许出现这些枚举字面量的位置（Adapter 消息类型除外，
 * 后者唯一来源是 adapter-protocol-v2.schema.json）。
 */
public final class ContractConsistencyValidator {

    public static final List<String> EXERCISE_STATES = Collections.unmodifiableList(Arrays.asList(
            "READY", "STARTING", "RUNNING", "PAUSING", "PAUSED",
            "RESUMING", "RECOVERING", "RECOVERY_FAILED", "ENDING", "ENDED"));

    public static final List<String> AIRCRAFT_LIFECYCLE = Collections.unmodifiableList(Arrays.asList(
            "PLANNED", "CREATE_REQUESTED", "CREATE_FAILED", "ACTIVE",
            "DELETE_REQUESTED", "DELETE_FAILED", "DELETED"));

    public static final List<String> INSTRUCTION_STATES = Collections.unmodifiableList(Arrays.asList(
            "RECEIVED", "VALIDATED", "BLOCKED", "DISPATCHING", "EXECUTING",
            "COMPLETED", "REPLACED", "FAILED", "TIMED_OUT", "CANCELLED", "REJECTED"));

    public static final List<String> BLOCKING_REASONS = Collections.unmodifiableList(Arrays.asList(
            "TRAINING_PAUSED", "PREDECESSOR_ACTIVE", "ENGINE_RECOVERING", "SCHEDULED_TIME_NOT_REACHED"));

    public static final List<String> CONTROL_CHANNELS = Collections.unmodifiableList(Arrays.asList(
            "LATERAL", "VERTICAL", "SPEED", "BUSINESS_FIELD", "SPECIAL"));

    /** 详细设计 12.1（2.2 修订后）：TRAINING_PAUSED 仅作为阻塞原因，不作为 HTTP 错误码。 */
    public static final List<String> ERROR_CODES = Collections.unmodifiableList(Arrays.asList(
            "TERMINAL_NOT_FOUND", "TERMINAL_NOT_IN_GROUP", "TRUSTED_IDENTITY_REJECTED",
            "TERMINAL_FREQUENCY_IN_USE", "AIRCRAFT_NOT_ASSIGNED",
            "AIRCRAFT_ASSIGNMENT_CHANGED", "REVISION_REQUIRED", "REVISION_CONFLICT",
            "IDEMPOTENCY_KEY_REUSED", "TRAINING_STATE_INVALID", "CHANNEL_DISPATCH_IN_PROGRESS",
            "INVALID_INSTRUCTION", "INITIAL_STATE_INCOMPLETE", "DUPLICATE_CALLSIGN",
            "CONFIRMATION_REQUIRED", "PERFORMANCE_LIMIT_EXCEEDED", "REFERENCE_NOT_FOUND",
            "PROCEDURE_STATE_INVALID", "FEATURE_PROFILE_NOT_CONFIGURED", "PROFILE_IMMUTABLE",
            "PROFILE_NOT_LOADED", "ADAPTER_ACK_TIMEOUT", "GUIDANCE_FAILED",
            "EVENT_CURSOR_EXPIRED", "ENGINE_RECOVERING", "DELETE_RECONCILIATION_REQUIRED"));

    public static final List<String> SSE_ENVELOPE_REQUIRED_FIELDS = Collections.unmodifiableList(Arrays.asList(
            "eventId", "streamEpoch", "deliverySequence", "groupSequence", "eventType",
            "exerciseGroupId", "terminalId", "systemTimeUtc", "simulationTimeSeconds", "payload"));

    /** validateAll 的聚合入口：任意一项不满足都汇入同一问题清单。 */
    public List<String> validateAll(Map<String, Object> openApi,
                                    Map<String, Object> sseSchema,
                                    Map<String, Object> adapterSchema,
                                    Map<String, Object> commandCatalog,
                                    List<Map<String, Object>> sharedVectors) {
        List<String> problems = new ArrayList<>();
        problems.addAll(validateEnums(openApi));
        problems.addAll(validateErrorCodes(openApi));
        problems.addAll(validateCommandTypes(openApi, commandCatalog));
        problems.addAll(validateAdapterMessages(adapterSchema, sharedVectors));
        problems.addAll(validateSseEnvelope(sseSchema));
        return problems;
    }

    /** 详细设计 5.1/5.2/5.4/6.1 的固定状态枚举必须与 OpenAPI 完全一致。 */
    public List<String> validateEnums(Map<String, Object> openApi) {
        List<String> problems = new ArrayList<>();
        checkSchemaEnum(openApi, "ExerciseGroupState", EXERCISE_STATES, problems);
        checkSchemaEnum(openApi, "AircraftLifecycle", AIRCRAFT_LIFECYCLE, problems);
        checkSchemaEnum(openApi, "InstructionState", INSTRUCTION_STATES, problems);
        checkSchemaEnum(openApi, "ControlChannel", CONTROL_CHANNELS, problems);
        checkSchemaEnum(openApi, "SchedulingMode",
                Arrays.asList("REPLACE", "AFTER_COMPLETION"), problems);

        List<String> blocking = enumAt(openApi,
                path("components", "schemas", "InstructionResponse", "properties", "blockingReasons", "items"));
        if (blocking == null) {
            problems.add("OpenAPI 缺少 InstructionResponse.blockingReasons.items 枚举");
        } else {
            checkSetEquals("InstructionResponse.blockingReasons", blocking, BLOCKING_REASONS, problems);
        }
        return problems;
    }

    /** 详细设计 12.1 错误码全集必须与 ErrorResponse.code 枚举一致。 */
    public List<String> validateErrorCodes(Map<String, Object> openApi) {
        List<String> problems = new ArrayList<>();
        List<String> codes = enumAt(openApi,
                path("components", "schemas", "ErrorResponse", "properties", "code"));
        if (codes == null) {
            problems.add("OpenAPI 缺少 ErrorResponse.code 枚举");
        } else {
            checkSetEquals("ErrorResponse.code", codes, ERROR_CODES, problems);
        }
        return problems;
    }

    /**
     * 命令目录与 OpenAPI 双向一致：
     * createsInstruction=true 的命令必须出现在 InstructionType 枚举中；
     * ACID/FRE/DEL 等路由型命令不得出现在枚举中；
     * 别名 token 全目录唯一，且不得与任何规范类型撞名。
     */
    public List<String> validateCommandTypes(Map<String, Object> openApi, Map<String, Object> commandCatalog) {
        List<String> problems = new ArrayList<>();
        List<String> instructionTypes = enumAt(openApi, path("components", "schemas", "InstructionType"));
        if (instructionTypes == null) {
            problems.add("OpenAPI 缺少 InstructionType 枚举");
            return problems;
        }
        Set<String> instructionTypeSet = new TreeSet<>(instructionTypes);

        Object commandsObject = commandCatalog.get("commands");
        if (!(commandsObject instanceof List) || ((List<?>) commandsObject).isEmpty()) {
            problems.add("命令目录缺少 commands 数组");
            return problems;
        }
        Set<String> aliasTokens = new TreeSet<>();
        for (Object entryObject : (List<?>) commandsObject) {
            if (!(entryObject instanceof Map)) {
                problems.add("命令目录存在非法条目");
                continue;
            }
            Map<?, ?> entry = (Map<?, ?>) entryObject;
            String type = String.valueOf(entry.get("type"));
            boolean createsInstruction = Boolean.TRUE.equals(entry.get("createsInstruction"));
            String channel = String.valueOf(entry.get("channel"));

            if (createsInstruction) {
                if (!instructionTypeSet.contains(type)) {
                    problems.add("命令 " + type + " 创建指令但不在 InstructionType 枚举中");
                }
            } else {
                if (instructionTypeSet.contains(type)) {
                    problems.add("命令 " + type + " 不创建指令却出现在 InstructionType 枚举中");
                }
                if (entry.get("routesTo") == null && !"SELECTION".equals(entry.get("kind"))) {
                    problems.add("路由型命令 " + type + " 必须声明 routesTo");
                }
            }
            if (!CONTROL_CHANNELS.contains(channel) && !"NONE".equals(channel)) {
                problems.add("命令 " + type + " 使用未知控制通道 " + channel);
            }
            Object aliases = entry.get("aliases");
            if (aliases instanceof List) {
                for (Object aliasObject : (List<?>) aliases) {
                    String alias = String.valueOf(aliasObject);
                    if (alias.equals(type)) {
                        problems.add("命令 " + type + " 把自身声明为别名");
                    }
                    if (instructionTypeSet.contains(alias)) {
                        problems.add("别名 " + alias + " 与规范命令类型撞名");
                    }
                    if (!aliasTokens.add(alias)) {
                        problems.add("别名 " + alias + " 在命令目录中重复出现");
                    }
                }
            }
        }
        for (String type : instructionTypeSet) {
            boolean declared = false;
            for (Object entryObject : (List<?>) commandsObject) {
                if (entryObject instanceof Map
                        && type.equals(((Map<?, ?>) entryObject).get("type"))
                        && Boolean.TRUE.equals(((Map<?, ?>) entryObject).get("createsInstruction"))) {
                    declared = true;
                    break;
                }
            }
            if (!declared) {
                problems.add("InstructionType 枚举值 " + type + " 在命令目录中没有 createsInstruction=true 条目");
            }
        }
        return problems;
    }

    /**
     * Adapter Schema 的 messageType 枚举是消息清单的唯一来源；
     * 共享向量的 messageType 必须落在枚举内，协议版本必须是 2.0。
     */
    public List<String> validateAdapterMessages(Map<String, Object> adapterSchema,
                                                List<Map<String, Object>> sharedVectors) {
        List<String> problems = new ArrayList<>();
        List<String> messageTypes = enumAt(adapterSchema, path("properties", "messageType"));
        if (messageTypes == null || messageTypes.isEmpty()) {
            problems.add("Adapter Schema 缺少 messageType 枚举");
            return problems;
        }
        Set<String> messageTypeSet = new TreeSet<>(messageTypes);
        if (!"2.0".equals(adapterSchema.get("x-protocol-version"))) {
            problems.add("Adapter Schema x-protocol-version 必须为 2.0");
        }
        for (Map<String, Object> vector : sharedVectors) {
            Object envelopeObject = vector.get("envelope");
            if (!(envelopeObject instanceof Map)) {
                problems.add("向量 " + vector.get("name") + " 缺少 envelope");
                continue;
            }
            Map<?, ?> envelope = (Map<?, ?>) envelopeObject;
            if (!messageTypeSet.contains(String.valueOf(envelope.get("messageType")))) {
                problems.add("向量 " + vector.get("name") + " 的 messageType 不在枚举内");
            }
        }
        return problems;
    }

    /** 详细设计 9.5：SSE 信封必需字段。 */
    public List<String> validateSseEnvelope(Map<String, Object> sseSchema) {
        List<String> problems = new ArrayList<>();
        Object required = sseSchema.get("required");
        if (!(required instanceof List)) {
            problems.add("SSE Schema 缺少 required 字段清单");
            return problems;
        }
        Set<String> requiredSet = new LinkedHashSet<>();
        for (Object field : (List<?>) required) {
            requiredSet.add(String.valueOf(field));
        }
        for (String field : SSE_ENVELOPE_REQUIRED_FIELDS) {
            if (!requiredSet.contains(field)) {
                problems.add("SSE Schema required 缺少字段 " + field);
            }
        }
        return problems;
    }

    private static void checkSchemaEnum(Map<String, Object> openApi, String schemaName,
                                        List<String> expected, List<String> problems) {
        List<String> actual = enumAt(openApi, path("components", "schemas", schemaName));
        if (actual == null) {
            problems.add("OpenAPI 缺少枚举 " + schemaName);
            return;
        }
        checkSetEquals(schemaName, actual, expected, problems);
    }

    private static void checkSetEquals(String name, List<String> actual, List<String> expected,
                                       List<String> problems) {
        Set<String> actualSet = new TreeSet<>(actual);
        Set<String> expectedSet = new TreeSet<>(expected);
        if (!actualSet.equals(expectedSet)) {
            Set<String> missing = new TreeSet<>(expectedSet);
            missing.removeAll(actualSet);
            Set<String> extra = new TreeSet<>(actualSet);
            extra.removeAll(expectedSet);
            problems.add(name + " 枚举不一致: 缺少 " + missing + " 多出 " + extra);
        }
    }

    private static List<String> enumAt(Map<String, Object> document, List<Object> path) {
        Object current = document;
        for (Object segment : path) {
            if (!(current instanceof Map)) {
                return null;
            }
            current = ((Map<?, ?>) current).get(segment);
        }
        if (!(current instanceof Map)) {
            return null;
        }
        Object enumObject = ((Map<?, ?>) current).get("enum");
        if (!(enumObject instanceof List)) {
            return null;
        }
        List<String> values = new ArrayList<>();
        for (Object value : (List<?>) enumObject) {
            values.add(Objects.toString(value, null));
        }
        return values;
    }

    private static List<Object> path(Object... segments) {
        return Arrays.asList(segments);
    }
}
