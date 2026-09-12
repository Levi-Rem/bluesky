package org.bluesky.training.instruction;

import org.bluesky.training.common.V2DomainException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** P10：PCA 变化率档位与帮助生成（详细设计 2.2 §7.1/§8.5）。 */
public class PcaRatePresets {

    public static final List<String> PRESETS =
            Arrays.asList("INSTANT", "MAX", "FAST", "NORMAL", "SLOW");

    /** 未启用 allowInstantPilotControl 时 INSTANT 必须禁用（详细设计 7.1）。 */
    public static String resolveRatePreset(String label, boolean allowInstantPilotControl) {
        String preset = label == null ? "NORMAL" : label.trim().toUpperCase();
        if (!PRESETS.contains(preset)) {
            throw new V2DomainException("INVALID_INSTRUCTION", 400,
                    "变化率档位必须是 INSTANT/MAX/FAST/NORMAL/SLOW: " + label,
                    Arrays.asList("ratePreset"));
        }
        if ("INSTANT".equals(preset) && !allowInstantPilotControl) {
            throw new V2DomainException("PERFORMANCE_LIMIT_EXCEEDED", 422,
                    "未启用 allowInstantPilotControl，INSTANT 档位禁用；其余档位仍受性能包线校验",
                    Arrays.asList("ratePreset"));
        }
        return preset;
    }

    /** 命令帮助/快捷键标签/OpenAPI 示例的唯一来源是命令目录（详细设计 7.10）。 */
    public static List<Map<String, Object>> generateHelp(InstructionCatalog catalog) {
        List<Map<String, Object>> help = new ArrayList<>();
        for (Map<String, Object> definition : catalog.allDefinitions()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("type", definition.get("type"));
            entry.put("channel", definition.get("channel"));
            entry.put("summary", definition.get("summary"));
            entry.put("aliases", definition.get("aliases"));
            help.add(entry);
        }
        return help;
    }
}
