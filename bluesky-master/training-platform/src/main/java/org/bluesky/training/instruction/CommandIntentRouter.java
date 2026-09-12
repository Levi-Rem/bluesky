package org.bluesky.training.instruction;

import org.bluesky.training.common.V2DomainException;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * P10：命令意图路由（详细设计 2.2 §7.10）。
 * FRE 路由到移交、DEL 路由到删除预览，两者绝不创建飞行指令。
 */
public class CommandIntentRouter {

    public enum Intent { INSTRUCTION, HANDOVER, DELETION_PREVIEW, UNKNOWN }

    public Map<String, Object> route(String text) {
        String trimmed = text == null ? "" : text.trim();
        String keyword = trimmed.isEmpty() ? "" : trimmed.split("\\s+")[0].toUpperCase();
        keyword = VendorAliasNormalizer.normalizeKeyword(keyword);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("originalText", trimmed);
        Intent intent;
        switch (keyword) {
            case "FRE":
                intent = Intent.HANDOVER;
                break;
            case "DEL":
                intent = Intent.DELETION_PREVIEW;
                break;
            default:
                if (VendorAliasNormalizer.KNOWN.contains(keyword)) {
                    intent = Intent.INSTRUCTION;
                } else {
                    intent = Intent.UNKNOWN;
                }
        }
        result.put("intent", intent.name());
        result.put("normalizedKeyword", keyword);
        return result;
    }

    /** FRE/DEL 不创建指令：路由命中即拦截（详细设计 7.10）。 */
    public static void requireInstructionIntent(String text) {
        CommandIntentRouter router = new CommandIntentRouter();
        Map<String, Object> routed = router.route(text);
        String intent = String.valueOf(routed.get("intent"));
        if ("HANDOVER".equals(intent)) {
            throw new V2DomainException("INVALID_INSTRUCTION", 400,
                    "FRE 解析为直接移交，请使用移交接口，不进入飞行控制通道",
                    Arrays.asList("text"));
        }
        if ("DELETION_PREVIEW".equals(intent)) {
            throw new V2DomainException("INVALID_INSTRUCTION", 400,
                    "DEL 进入删除预览流程，不创建指令", Arrays.asList("text"));
        }
        if ("UNKNOWN".equals(intent)) {
            throw new V2DomainException("INVALID_INSTRUCTION", 400,
                    "未知命令: " + text, Arrays.asList("text"));
        }
    }
}
