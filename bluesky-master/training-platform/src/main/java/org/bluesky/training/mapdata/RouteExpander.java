package org.bluesky.training.mapdata;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class RouteExpander {
    private final Map<String, RuntimeNavigationPoint> pointsByCode = new HashMap<>();
    private final Map<String, RuntimeAirway> airwaysByCode = new HashMap<>();

    public RouteExpander(List<RuntimeNavigationPoint> points, List<RuntimeAirway> airways) {
        for (RuntimeNavigationPoint point : points) {
            String code = normalize(point.getCode());
            if (pointsByCode.put(code, point) != null) {
                throw new ReferenceDataException("AMBIGUOUS_NAVIGATION_POINT", "导航点代码重复：" + code);
            }
        }
        for (RuntimeAirway airway : airways) {
            String code = normalize(airway.getCode());
            if (airwaysByCode.put(code, airway) != null) {
                throw new ReferenceDataException("AIRWAY_PATH_AMBIGUOUS", "航路代码重复：" + code);
            }
        }
    }

    public List<RuntimeNavigationPoint> expand(List<String> rawTokens) {
        List<String> tokens = new ArrayList<>();
        if (rawTokens != null) {
            for (String raw : rawTokens) {
                String token = normalize(raw);
                if (!token.isEmpty() && !"DCT".equals(token)) tokens.add(token);
            }
        }
        if (tokens.isEmpty()) return new ArrayList<>();

        List<RuntimeNavigationPoint> result = new ArrayList<>();
        RuntimeNavigationPoint current = resolvePoint(tokens.get(0));
        result.add(current);
        int index = 1;
        while (index < tokens.size()) {
            String token = tokens.get(index);
            RuntimeAirway airway = airwaysByCode.get(token);
            if (airway != null) {
                if (pointsByCode.containsKey(token)) {
                    throw error("AMBIGUOUS_ROUTE_TOKEN", "令牌同时匹配点和航路：" + token);
                }
                if (index + 1 >= tokens.size()) {
                    throw error("AIRWAY_ENDPOINT_NOT_FOUND", "航路缺少离开点：" + token);
                }
                RuntimeNavigationPoint exit = resolvePoint(tokens.get(index + 1));
                appendAirway(result, airway, current.getCode(), exit.getCode());
                current = exit;
                index += 2;
            } else {
                RuntimeNavigationPoint next = resolvePoint(token);
                appendDistinct(result, next);
                current = next;
                index++;
            }
        }
        return result;
    }

    private void appendAirway(List<RuntimeNavigationPoint> result, RuntimeAirway airway,
                              String entryCode, String exitCode) {
        List<String> codes = airway.getPointCodes();
        int entry = uniqueIndex(codes, normalize(entryCode));
        int exit = uniqueIndex(codes, normalize(exitCode));
        if (entry < 0 || exit < 0) {
            throw error("AIRWAY_ENDPOINT_NOT_FOUND", "航路端点不存在于 " + airway.getCode());
        }
        if (entry == exit) return;
        boolean forward = entry < exit;
        if (!allows(airway.getDirection(), forward)) {
            throw error("AIRWAY_DIRECTION_NOT_ALLOWED", "航路方向不允许：" + airway.getCode());
        }
        int step = forward ? 1 : -1;
        int cursor = entry;
        while (cursor != exit) {
            int segmentIndex = forward ? cursor : cursor - 1;
            if (!allows(airway.getSegmentDirections().get(segmentIndex), forward)) {
                throw error("AIRWAY_DIRECTION_NOT_ALLOWED", "航段方向不允许：" + airway.getCode());
            }
            cursor += step;
            appendDistinct(result, resolvePoint(codes.get(cursor)));
        }
    }

    private int uniqueIndex(List<String> codes, String target) {
        int found = -1;
        for (int i = 0; i < codes.size(); i++) {
            if (target.equals(normalize(codes.get(i)))) {
                if (found >= 0) throw error("AIRWAY_PATH_AMBIGUOUS", "航路端点重复：" + target);
                found = i;
            }
        }
        return found;
    }

    private boolean allows(String direction, boolean forward) {
        String value = normalize(direction);
        return "BOTH".equals(value) || (forward && "FORWARD".equals(value))
                || (!forward && "REVERSE".equals(value));
    }

    private RuntimeNavigationPoint resolvePoint(String token) {
        String code = normalize(token);
        if (unsupported(code)) {
            throw error("UNSUPPORTED_ROUTE_TOKEN", "不支持的航线标记：" + code);
        }
        RuntimeNavigationPoint point = pointsByCode.get(code);
        if (point == null) throw error("UNKNOWN_NAVIGATION_POINT", "导航点不存在：" + code);
        return point;
    }

    private boolean unsupported(String token) {
        return token.startsWith("SID") || token.startsWith("STAR")
                || token.contains(",") || token.contains("/");
    }

    private void appendDistinct(List<RuntimeNavigationPoint> result, RuntimeNavigationPoint point) {
        if (result.isEmpty() || !result.get(result.size() - 1).getCode().equals(point.getCode())) {
            result.add(point);
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private ReferenceDataException error(String code, String message) {
        return new ReferenceDataException(code, message);
    }
}
