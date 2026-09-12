package org.bluesky.training.instruction;

import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P10：v2 文本指令解析入口（评审 P0-10；详细设计 9.4/7.10）。
 * 原始文本 → 厂商别名规范化 → 意图路由（FRE/DEL/未知拦截）→ 按目录分发解析器
 * → 结构化 {type, parameters}。参考数据依赖的命令（程序/跑道/VOR/航段）
 * 以空参考集解析，缺少参考数据时明确拒绝并指出缺少项（详细设计 11）。
 */
@Component
public class CommandTextParser {

    /** 解析结果：type/parameters/normalizedText（报告同存原始与规范化文本，详细设计 7.10）。 */
    public Map<String, Object> parse(String text, String destinationAirport) {
        return parse(text,destinationAirport,0.0);
    }

    public Map<String, Object> parse(String text, String destinationAirport, double now) {
        CommandIntentRouter.requireInstructionIntent(text);
        String normalized = VendorAliasNormalizer.normalize(text);
        String keyword = normalized.split("\\s+")[0].toUpperCase();

        Map<String, Object> parameters = parseByKeyword(keyword, normalized, destinationAirport,now);
        Map<String, Object> command = new LinkedHashMap<>();
        command.put("type", canonicalType(keyword, parameters));
        command.put("parameters", parameters);
        command.put("normalizedText", normalized);
        return command;
    }

    private static String canonicalType(String keyword, Map<String, Object> parameters) {
        // LEFT/RIGHT 是 HDG 的转向形式；目录中独立成类型
        if ("NML".equals(keyword)) {
            return "NML";
        }
        if ("IDENT".equals(keyword)) {
            return "IDENT";
        }
        if ("NSPEED".equals(keyword)) {
            return "NSPEED";
        }
        return keyword;
    }

    private static Map<String, Object> parseByKeyword(String keyword, String text,
                                                      String destinationAirport, double now) {
        List<String> noReference = Collections.emptyList();
        switch (keyword) {
            case "HDG":
                return BasicCommandParsers.parseHeading(text);
            case "LEFT":
            case "RIGHT":
                return BasicCommandParsers.parseTurn(text);
            case "ALT":
                return BasicCommandParsers.parseAltitude(text);
            case "VS":
                return BasicCommandParsers.parseVerticalSpeed(text);
            case "SPD":
                return BasicCommandParsers.parseSpeed(text);
            case "MACH":
                return BasicCommandParsers.parseMach(text);
            case "DCT":
                return NavigationCommandParsers.parseDirectTo(text);
            case "RTE":
                return NavigationCommandParsers.parseRoute(text, destinationAirport);
            case "RESUME":
                return NavigationCommandParsers.parseResume(text);
            case "ORBIT":
                return NavigationCommandParsers.parseOrbit(text);
            case "HOLD":
                return NavigationCommandParsers.parseHold(text);
            case "OFFSET":
                return NavigationCommandParsers.parseOffset(text);
            case "VOR":
                return NavigationCommandParsers.parseVor(text, noReference);
            case "SIDSTAR":
                return ProcedureCommandParsers.parseSidStar(text, noReference);
            case "P_LEVEL":
                return ProcedureCommandParsers.parseLegLevel(text, noReference);
            case "P_TIME":
                return ProcedureCommandParsers.parseLegTime(text, noReference, now);
            case "MISSED":
                return ProcedureCommandParsers.parseMissed(text, noReference);
            case "TAKEOFF":
                return LandingCommandParsers.parseTakeoff(text, noReference, now);
            case "ILS":
                return LandingCommandParsers.parseIls(text, noReference, destinationAirport);
            case "SQK":
                return TransponderCommandParsers.parseSquawk(text);
            case "SSRMODE":
                return TransponderCommandParsers.parseSsrMode(text);
            case "NML":
                return TransponderCommandParsers.buildNormalRestore();
            case "IDENT":
                return TransponderCommandParsers.buildIdent(null);
            case "NSPEED":
                return TransponderCommandParsers.buildNormalSpeed();
            case "ID":
            case "DECOMP": {
                // profile 能力槽（详细设计 7.8）：调度由已发布 profile 驱动
                Map<String, Object> slot = new java.util.LinkedHashMap<>();
                slot.put("operationType", keyword);
                java.util.List<String> parts = PerformanceGuard.tokenize(text);
                slot.put("mode", parts.size() > 1 ? parts.get(1).toUpperCase() : null);
                slot.put("affectedChannels", java.util.Arrays.asList("VERTICAL"));
                slot.put("adapterRequired", true);
                return slot;
            }
            default:
                throw new org.bluesky.training.common.V2DomainException(
                        "INVALID_INSTRUCTION", 400,
                        "文本命令暂不支持或缺少参考数据: " + text,
                        Arrays.asList("text"));
        }
    }
}
