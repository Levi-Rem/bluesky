package org.bluesky.training.instruction;

import org.bluesky.training.contract.ContractCatalog;
import org.bluesky.training.common.V2DomainException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P09：机器可读命令定义（详细设计 2.2 §6.1/§7.10）。
 * 唯一来源是 docs/contracts/command-catalog-v2.json（P00 定桩），本类不手抄命令清单。
 */
@org.springframework.stereotype.Component
public class InstructionCatalog {

    /** 复合指令影响的子通道（详细设计 6.2 接管策略表）。 */
    private static final Map<String, List<String>> COMPOSITE_CHANNELS = new LinkedHashMap<>();

    static {
        COMPOSITE_CHANNELS.put("TAKEOFF", Arrays.asList("LATERAL", "VERTICAL", "SPEED"));
        COMPOSITE_CHANNELS.put("ILS", Arrays.asList("LATERAL", "VERTICAL"));
        COMPOSITE_CHANNELS.put("MISSED", Arrays.asList("LATERAL", "VERTICAL"));
        // ID/DECOMP 由已发布 profile 声明，P14 接入
    }

    private final Map<String, Map<String, Object>> definitions;

    public InstructionCatalog() {
        this(new ContractCatalog().loadCommandCatalog());
    }

    @SuppressWarnings("unchecked")
    public InstructionCatalog(Map<String, Object> commandCatalog) {
        Object commands = commandCatalog.get("commands");
        Map<String, Map<String, Object>> indexed = new LinkedHashMap<>();
        if (commands instanceof List) {
            for (Object entry : (List<?>) commands) {
                if (entry instanceof Map) {
                    Map<String, Object> definition = (Map<String, Object>) entry;
                    indexed.put(String.valueOf(definition.get("type")), definition);
                }
            }
        }
        this.definitions = Collections.unmodifiableMap(indexed);
    }

    public Map<String, Object> definitionFor(String type) {
        Map<String, Object> definition = definitions.get(type);
        if (definition == null) {
            throw new V2DomainException("INVALID_INSTRUCTION", 400,
                    "未知指令类型: " + type, Arrays.asList("type"));
        }
        return definition;
    }

    public boolean createsInstruction(String type) {
        Map<String, Object> definition = definitions.get(type);
        return definition != null && Boolean.TRUE.equals(definition.get("createsInstruction"));
    }

    public List<Map<String, Object>> allDefinitions() {
        return new ArrayList<>(definitions.values());
    }

    public String channelOf(String type) {
        return String.valueOf(definitionFor(type).get("channel"));
    }

    /** 受影响通道：普通指令单通道，复合指令多通道（详细设计 6.1）。 */
    public List<String> affectedChannelsFor(String type) {
        List<String> composite = COMPOSITE_CHANNELS.get(type);
        if (composite != null) {
            return composite;
        }
        return Collections.singletonList(channelOf(type));
    }

    /**
     * 冲突键（详细设计 5.0：UNIQUE(aircraft_id, conflict_key, sequence_number)）。
     * BUSINESS_FIELD 按字段独立；SPECIAL 复合按每个受影响通道各占一键。
     */
    public List<String> conflictKeysFor(String type, String aircraftId) {
        List<String> keys = new ArrayList<>();
        String channel = channelOf(type);
        switch (channel) {
            case "LATERAL":
            case "VERTICAL":
            case "SPEED":
                keys.add(channel + ":" + aircraftId);
                break;
            case "BUSINESS_FIELD":
                keys.add("BUSINESS_FIELD:" + type + ":" + aircraftId);
                break;
            case "SPECIAL":
                for (String affected : affectedChannelsFor(type)) {
                    keys.add(affected + ":" + aircraftId);
                }
                break;
            default:
                throw new V2DomainException("INVALID_INSTRUCTION", 400,
                        "命令不进入飞行控制通道: " + type, Arrays.asList("type"));
        }
        return keys;
    }
}
