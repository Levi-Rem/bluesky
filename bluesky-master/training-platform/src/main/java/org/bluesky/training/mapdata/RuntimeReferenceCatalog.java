package org.bluesky.training.mapdata;

import org.bluesky.training.reference.ReferenceItem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class RuntimeReferenceCatalog {
    private static final Set<String> POINT_TYPES = new HashSet<>();

    static {
        Collections.addAll(POINT_TYPES, "WAYPOINT", "AIRPORT", "VOR", "NDB", "DME", "VOR_DME", "ILS");
    }

    private final MapLayersResponse snapshot;
    private final List<RuntimeNavigationPoint> points;
    private final List<RuntimeAirway> airways;
    private final Map<String, RuntimeNavigationPoint> pointsByCode;
    private final Map<String, Integer> counts;
    private final RouteExpander routeExpander;

    private RuntimeReferenceCatalog(MapLayersResponse snapshot,
                                    List<RuntimeNavigationPoint> points,
                                    List<RuntimeAirway> airways) {
        this.snapshot = snapshot;
        this.points = Collections.unmodifiableList(new ArrayList<>(points));
        this.airways = Collections.unmodifiableList(new ArrayList<>(airways));
        Map<String, RuntimeNavigationPoint> index = new LinkedHashMap<>();
        Map<String, Integer> typeCounts = new LinkedHashMap<>();
        for (RuntimeNavigationPoint point : points) {
            String code = normalize(point.getCode());
            if (index.put(code, point) != null) {
                throw error("AMBIGUOUS_NAVIGATION_POINT", "导航点代码重复：" + code);
            }
            typeCounts.put(point.getType(), typeCounts.getOrDefault(point.getType(), 0) + 1);
        }
        this.pointsByCode = Collections.unmodifiableMap(index);
        this.counts = Collections.unmodifiableMap(typeCounts);
        this.routeExpander = new RouteExpander(points, airways);
    }

    public static RuntimeReferenceCatalog from(MapLayersResponse response) {
        if (response == null || !response.isAvailable() || response.getLayers() == null) {
            throw error("REFERENCE_DATA_NOT_READY", "运行态地图快照不可用");
        }
        Map<String, Object> waypointLayer = layer(response.getLayers(), "WAYPOINT");
        Map<String, Object> airwayLayer = layer(response.getLayers(), "AIRWAY");
        List<RuntimeNavigationPoint> points = parsePoints(features(waypointLayer));
        if (points.isEmpty()) throw error("REFERENCE_DATA_NOT_READY", "标准导航点数量为 0");
        List<RuntimeAirway> airways = parseAirways(features(airwayLayer), points);
        return new RuntimeReferenceCatalog(response, points, airways);
    }

    public MapLayersResponse snapshot() { return snapshot; }
    public List<RuntimeNavigationPoint> points() { return points; }
    public List<RuntimeAirway> airways() { return airways; }
    public Map<String, Integer> counts() { return counts; }
    public RuntimeNavigationPoint resolve(String code) {
        RuntimeNavigationPoint point = pointsByCode.get(normalize(code));
        if (point == null) throw error("UNKNOWN_NAVIGATION_POINT", "导航点不存在：" + code);
        return point;
    }
    public List<RuntimeNavigationPoint> expandRoute(List<String> tokens) { return routeExpander.expand(tokens); }

    public List<ReferenceItem> searchAirports(String query, int limit) {
        return search(query, limit, true);
    }

    public List<ReferenceItem> searchWaypoints(String query, int limit) {
        return search(query, limit, false);
    }

    private List<ReferenceItem> search(String query, int limit, boolean airports) {
        final String normalized = normalize(query);
        List<RuntimeNavigationPoint> candidates = new ArrayList<>();
        for (RuntimeNavigationPoint point : points) {
            if (airports != "AIRPORT".equals(point.getType())) continue;
            String name = point.getName() == null ? "" : point.getName().toUpperCase(Locale.ROOT);
            if (normalized.isEmpty() || point.getCode().contains(normalized) || name.contains(normalized)) {
                candidates.add(point);
            }
        }
        candidates.sort(Comparator
                .comparingInt((RuntimeNavigationPoint point) -> point.getCode().startsWith(normalized) ? 0 : 1)
                .thenComparing(RuntimeNavigationPoint::getCode)
                .thenComparing(RuntimeNavigationPoint::getId));
        List<ReferenceItem> result = new ArrayList<>();
        int safeLimit = Math.max(0, Math.min(limit, 20));
        for (int i = 0; i < candidates.size() && i < safeLimit; i++) {
            RuntimeNavigationPoint point = candidates.get(i);
            result.add(new ReferenceItem(point.getCode(), point.getName(),
                    point.getLatitude(), point.getLongitude()));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static List<RuntimeNavigationPoint> parsePoints(List<Map<String, Object>> features) {
        List<RuntimeNavigationPoint> result = new ArrayList<>();
        for (Map<String, Object> feature : features) {
            String featureId = required(feature.get("featureId"), "导航点 featureId 缺失");
            String code = normalize(required(feature.get("code"), "导航点代码缺失"));
            String type = normalize(required(feature.get("pointType"), "导航点类型缺失"));
            if (!POINT_TYPES.contains(type)) throw error("INVALID_NAVIGATION_POINT", "导航点类型不支持：" + type);
            Object geometryValue = feature.get("geometry");
            if (!(geometryValue instanceof Map)) throw error("INVALID_NAVIGATION_POINT", "导航点几何缺失：" + code);
            Map<String, Object> geometry = (Map<String, Object>) geometryValue;
            if (!"Point".equals(geometry.get("type")) || !(geometry.get("coordinates") instanceof List)) {
                throw error("INVALID_NAVIGATION_POINT", "导航点几何类型非法：" + code);
            }
            List<?> coordinate = (List<?>) geometry.get("coordinates");
            if (coordinate.size() < 2 || !(coordinate.get(0) instanceof Number)
                    || !(coordinate.get(1) instanceof Number)) {
                throw error("INVALID_NAVIGATION_POINT", "导航点坐标缺失：" + code);
            }
            double longitude = ((Number) coordinate.get(0)).doubleValue();
            double latitude = ((Number) coordinate.get(1)).doubleValue();
            if (!Double.isFinite(longitude) || longitude < -180d || longitude > 180d
                    || !Double.isFinite(latitude) || latitude < -90d || latitude > 90d) {
                throw error("INVALID_NAVIGATION_POINT", "导航点坐标越界：" + code);
            }
            Integer elevation = feature.get("elevationMeters") instanceof Number
                    ? ((Number) feature.get("elevationMeters")).intValue() : null;
            result.add(new RuntimeNavigationPoint(rawId(featureId), code,
                    text(feature.get("name")), type, latitude, longitude, elevation));
        }
        return result;
    }

    private static List<RuntimeAirway> parseAirways(List<Map<String, Object>> features,
                                                     List<RuntimeNavigationPoint> points) {
        Set<String> pointCodes = new HashSet<>();
        for (RuntimeNavigationPoint point : points) pointCodes.add(point.getCode());
        List<RuntimeAirway> result = new ArrayList<>();
        Set<String> airwayCodes = new HashSet<>();
        for (Map<String, Object> feature : features) {
            String featureId = required(feature.get("featureId"), "航路 featureId 缺失");
            String code = normalize(required(feature.get("code"), "航路代码缺失"));
            if (!airwayCodes.add(code)) throw error("AIRWAY_PATH_AMBIGUOUS", "航路代码重复：" + code);
            List<String> codes = stringList(feature.get("pointCodes"), "航路点代码缺失：" + code);
            List<String> directions = stringList(feature.get("segmentDirections"), "航段方向缺失：" + code);
            if (codes.size() < 2 || directions.size() != codes.size() - 1) {
                throw error("INVALID_AIRWAY", "航路点或方向数量不一致：" + code);
            }
            for (String pointCode : codes) {
                if (!pointCodes.contains(pointCode)) {
                    throw error("INVALID_AIRWAY", "航路引用未知点：" + pointCode);
                }
            }
            result.add(new RuntimeAirway(rawId(featureId), code,
                    direction(feature.get("airwayDirection")), codes, normalizeDirections(directions)));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> layer(List<Map<String, Object>> layers, String category) {
        for (Map<String, Object> layer : layers) {
            if (category.equals(String.valueOf(layer.get("category")))) return layer;
        }
        throw error("REFERENCE_DATA_NOT_READY", "运行态地图缺少图层：" + category);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> features(Map<String, Object> layer) {
        Object value = layer.get("features");
        if (!(value instanceof List)) throw error("REFERENCE_DATA_NOT_READY", "图层要素列表缺失");
        return (List<Map<String, Object>>) value;
    }

    private static List<String> stringList(Object value, String message) {
        if (!(value instanceof List)) throw error("INVALID_AIRWAY", message);
        List<String> result = new ArrayList<>();
        for (Object item : (List<?>) value) result.add(normalize(required(item, message)));
        return result;
    }

    private static List<String> normalizeDirections(List<String> values) {
        List<String> result = new ArrayList<>();
        for (String value : values) result.add(direction(value));
        return result;
    }

    private static String direction(Object value) {
        String direction = value == null ? "BOTH" : normalize(String.valueOf(value));
        if ("BOTH".equals(direction) || "FORWARD".equals(direction) || "REVERSE".equals(direction)) {
            return direction;
        }
        throw error("INVALID_AIRWAY", "航路方向非法：" + direction);
    }

    private static String rawId(String featureId) {
        int separator = featureId.indexOf(':');
        String id = separator >= 0 ? featureId.substring(separator + 1) : featureId;
        return required(id, "要素 ID 缺失");
    }

    private static String required(Object value, String message) {
        String result = text(value);
        if (result == null || result.trim().isEmpty()) throw error("INVALID_RUNTIME_NAV_DATA", message);
        return result.trim();
    }

    private static String text(Object value) { return value == null ? null : String.valueOf(value); }
    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
    private static ReferenceDataException error(String code, String message) {
        return new ReferenceDataException(code, message);
    }
}
