package org.bluesky.dataprep.map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class RuntimeMapService {
    private static final Logger log = LoggerFactory.getLogger(RuntimeMapService.class);
    private static final TypeReference<Map<String, Object>> JSON_OBJECT =
            new TypeReference<Map<String, Object>>() { };

    private final RuntimeMapMapper mapper;
    private final ObjectMapper objectMapper;

    public RuntimeMapService(RuntimeMapMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> snapshot() {
        List<RuntimeMapLayer> layers = new ArrayList<>();
        layers.add(waypoints());
        layers.add(airways());
        layers.add(physicalSectors());
        layers.add(weather());
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("layers", layers);
        return response;
    }

    private RuntimeMapLayer waypoints() {
        RuntimeMapLayer layer = new RuntimeMapLayer("WAYPOINT", "航路点");
        Set<String> codes = new HashSet<>();
        for (Map<String, Object> row : mapper.selectWaypoints()) {
            String id = string(row.get("id"));
            try {
                requireText(id, "导航点 ID 缺失");
                String code = normalizedCode(row.get("code"));
                if (!codes.add(code)) throw new IllegalArgumentException("导航点代码重复：" + code);
                String pointType = normalizedPointType(row.get("pointType"));
                Map<String, Object> extra = new LinkedHashMap<>();
                extra.put("pointType", pointType);
                if (row.get("elevationMeters") != null) {
                    if (!(row.get("elevationMeters") instanceof Number)) {
                        throw new IllegalArgumentException("高程不合法");
                    }
                    extra.put("elevationMeters", ((Number) row.get("elevationMeters")).intValue());
                }
                String prefix = "airport".equals(string(row.get("entityKind"))) ? "airport:" : "waypoint:";
                layer.addFeature(prefix + id, "WAYPOINT", code,
                        string(row.get("name")), point(row.get("longitude"), row.get("latitude")), extra);
            } catch (IllegalArgumentException error) {
                throw invalid("导航点数据非法 id=" + id + " reason=" + error.getMessage(), error);
            }
        }
        if (layer.getCount() == 0) throw invalid("标准导航点数量为 0", null);
        return layer;
    }

    private RuntimeMapLayer airways() {
        RuntimeMapLayer layer = new RuntimeMapLayer("AIRWAY", "航线");
        Map<String, List<Map<String, Object>>> segmentsByAirway = new LinkedHashMap<>();
        for (Map<String, Object> segment : mapper.selectAirwaySegments()) {
            segmentsByAirway.computeIfAbsent(string(segment.get("airwayId")), ignored -> new ArrayList<>())
                    .add(segment);
        }
        Set<String> airwayCodes = new HashSet<>();
        for (Map<String, Object> row : mapper.selectAirways()) {
            String id = string(row.get("id"));
            try {
                requireText(id, "航路 ID 缺失");
                String code = normalizedCode(row.get("code"));
                if (!airwayCodes.add(code)) throw new IllegalArgumentException("航路代码重复：" + code);
                List<Map<String, Object>> segments = segmentsByAirway.get(id);
                if (segments == null || segments.isEmpty()) {
                    throw new IllegalArgumentException("航路有效航段少于一个");
                }
                List<String> pointIds = new ArrayList<>();
                List<String> pointCodes = new ArrayList<>();
                List<String> segmentDirections = new ArrayList<>();
                List<List<Double>> path = new ArrayList<>();
                String previousEndId = null;
                int previousOrder = Integer.MIN_VALUE;
                for (Map<String, Object> segment : segments) {
                    int order = integer(segment.get("orderNo"));
                    if (order <= previousOrder) throw new IllegalArgumentException("航段顺序重复或倒序");
                    String startId = requiredText(segment.get("startPointId"), "航段起点 ID 缺失");
                    String endId = requiredText(segment.get("endPointId"), "航段终点 ID 缺失");
                    String startCode = normalizedCode(segment.get("startPointCode"));
                    String endCode = normalizedCode(segment.get("endPointCode"));
                    List<Double> start = coordinate(segment.get("startLongitude"), segment.get("startLatitude"));
                    List<Double> end = coordinate(segment.get("endLongitude"), segment.get("endLatitude"));
                    if (previousEndId == null) {
                        pointIds.add(startId);
                        pointCodes.add(startCode);
                        path.add(start);
                    } else if (!previousEndId.equals(startId)) {
                        throw new IllegalArgumentException("相邻航段未连接");
                    }
                    if (pointIds.get(pointIds.size() - 1).equals(endId)) {
                        throw new IllegalArgumentException("航路包含相邻重复点");
                    }
                    pointIds.add(endId);
                    pointCodes.add(endCode);
                    path.add(end);
                    segmentDirections.add(normalizedDirection(segment.get("segmentDirection")));
                    previousEndId = endId;
                    previousOrder = order;
                }
                if (path.size() < 2) throw new IllegalArgumentException("航线有效顶点少于两个");
                Map<String, Object> extra = new LinkedHashMap<>();
                extra.put("airwayDirection", normalizedDirection(row.get("airwayDirection")));
                extra.put("pointIds", pointIds);
                extra.put("pointCodes", pointCodes);
                extra.put("segmentDirections", segmentDirections);
                layer.addFeature("airway:" + id, "AIRWAY", code,
                        string(row.get("name")), geometry("LineString", path), extra);
            } catch (IllegalArgumentException error) {
                throw invalid("航路数据非法 id=" + id + " reason=" + error.getMessage(), error);
            }
        }
        return layer;
    }

    private RuntimeMapLayer physicalSectors() {
        RuntimeMapLayer layer = new RuntimeMapLayer("PHYSICAL_SECTOR", "扇区");
        CoordinateGroups boundaries = coordinatesBy(
                mapper.selectPhysicalSectorPoints(), "sectorId");
        for (Map<String, Object> row : mapper.selectPhysicalSectors()) {
            String id = string(row.get("id"));
            try {
                rejectInvalidGroup(boundaries, id, "扇区包含缺失或非法顶点");
                List<List<Double>> boundary = removeAdjacentDuplicates(boundaries.coordinates.get(id));
                if (boundary.size() < 3) throw new IllegalArgumentException("扇区有效顶点少于三个");
                close(boundary);
                List<List<List<Double>>> rings = new ArrayList<>();
                rings.add(boundary);
                layer.addFeature("physical-sector:" + id, "PHYSICAL_SECTOR", null,
                        string(row.get("name")), geometry("Polygon", rings));
            } catch (IllegalArgumentException error) {
                warn("PHYSICAL_SECTOR", id, error);
            }
        }
        return layer;
    }

    private RuntimeMapLayer weather() {
        RuntimeMapLayer layer = new RuntimeMapLayer("WEATHER", "天气");
        for (Map<String, Object> row : mapper.selectWindPoints()) {
            String id = string(row.get("id"));
            try {
                layer.addFeature("wind-point:" + id, "WIND_FIELD_POINT", string(row.get("code")),
                        string(row.get("name")), point(row.get("longitude"), row.get("latitude")));
            } catch (IllegalArgumentException error) {
                warn("WIND_FIELD_POINT", id, error);
            }
        }
        for (Map<String, Object> row : mapper.selectSignificantWeatherAreas()) {
            String id = string(row.get("id"));
            try {
                Map<String, Object> geometry = objectMapper.readValue(
                        string(row.get("boundary")), JSON_OBJECT);
                String type = string(geometry.get("type"));
                if (!"Polygon".equals(type) && !"MultiPolygon".equals(type)) {
                    throw new IllegalArgumentException("重要天气区域必须为 Polygon 或 MultiPolygon");
                }
                layer.addFeature("significant-weather:" + id, "SIGNIFICANT_WEATHER_AREA",
                        string(row.get("code")), string(row.get("name")), geometry);
            } catch (Exception error) {
                warn("SIGNIFICANT_WEATHER_AREA", id, error);
            }
        }
        return layer;
    }

    private CoordinateGroups coordinatesBy(List<Map<String, Object>> rows, String key) {
        CoordinateGroups result = new CoordinateGroups();
        for (Map<String, Object> row : rows) {
            String id = string(row.get(key));
            try {
                result.coordinates.computeIfAbsent(id, ignored -> new ArrayList<>())
                        .add(coordinate(row.get("longitude"), row.get("latitude")));
            } catch (IllegalArgumentException error) {
                result.invalidIds.add(id);
                warn("COORDINATE", id, error);
            }
        }
        return result;
    }

    private void rejectInvalidGroup(CoordinateGroups groups, String id, String message) {
        if (groups.invalidIds.contains(id)) throw new IllegalArgumentException(message);
    }

    private Map<String, Object> point(Object longitude, Object latitude) {
        return geometry("Point", coordinate(longitude, latitude));
    }

    private List<Double> coordinate(Object longitude, Object latitude) {
        double lon = number(longitude);
        double lat = number(latitude);
        if (!Double.isFinite(lon) || !Double.isFinite(lat)
                || lon < -180d || lon > 180d || lat < -90d || lat > 90d) {
            throw new IllegalArgumentException("经纬度不合法");
        }
        List<Double> coordinate = new ArrayList<>();
        coordinate.add(lon);
        coordinate.add(lat);
        return coordinate;
    }

    private Map<String, Object> geometry(String type, Object coordinates) {
        Map<String, Object> geometry = new LinkedHashMap<>();
        geometry.put("type", type);
        geometry.put("coordinates", coordinates);
        return geometry;
    }

    private List<List<Double>> removeAdjacentDuplicates(List<List<Double>> coordinates) {
        List<List<Double>> result = new ArrayList<>();
        if (coordinates == null) return result;
        for (List<Double> coordinate : coordinates) {
            if (result.isEmpty() || !result.get(result.size() - 1).equals(coordinate)) {
                result.add(new ArrayList<>(coordinate));
            }
        }
        return result;
    }

    private void close(List<List<Double>> ring) {
        if (!ring.get(0).equals(ring.get(ring.size() - 1))) {
            ring.add(new ArrayList<>(ring.get(0)));
        }
    }

    private double number(Object value) {
        if (!(value instanceof Number)) throw new IllegalArgumentException("坐标缺失");
        return ((Number) value).doubleValue();
    }

    private int integer(Object value) {
        if (!(value instanceof Number)) throw new IllegalArgumentException("顺序缺失");
        return ((Number) value).intValue();
    }

    private String normalizedCode(Object value) {
        String code = string(value);
        requireText(code, "代码缺失");
        return code.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizedPointType(Object value) {
        String type = requiredText(value, "导航点类型缺失").toUpperCase(Locale.ROOT);
        if ("FIX".equals(type) || "REPORT".equals(type) || "WAYPOINT".equals(type)) return "WAYPOINT";
        if ("AIRPORT".equals(type) || "AIRPORT_I".equals(type)) return "AIRPORT";
        if ("VORDME".equals(type)) return "VOR_DME";
        if ("VOR".equals(type) || "NDB".equals(type) || "DME".equals(type)
                || "VOR_DME".equals(type) || "ILS".equals(type)) return type;
        throw new IllegalArgumentException("导航点类型不支持：" + type);
    }

    private String normalizedDirection(Object value) {
        String direction = value == null ? "BOTH" : string(value).trim().toUpperCase(Locale.ROOT);
        if (direction.isEmpty() || "TWO_WAY".equals(direction) || "BOTH".equals(direction)) return "BOTH";
        if ("ONE_WAY".equals(direction) || "FORWARD".equals(direction)) return "FORWARD";
        if ("REVERSE".equals(direction)) return "REVERSE";
        throw new IllegalArgumentException("方向不支持：" + direction);
    }

    private String requiredText(Object value, String message) {
        String text = string(value);
        requireText(text, message);
        return text.trim();
    }

    private void requireText(String value, String message) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(message);
    }

    private RuntimeMapDataException invalid(String message, Throwable cause) {
        return cause == null ? new RuntimeMapDataException(message) : new RuntimeMapDataException(message, cause);
    }

    private String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private void warn(String type, String id, Exception error) {
        log.warn("跳过无效运行态地图要素 type={} id={} reason={}", type, id, error.getMessage());
    }

    private static final class CoordinateGroups {
        private final Map<String, List<List<Double>>> coordinates = new LinkedHashMap<>();
        private final Set<String> invalidIds = new HashSet<>();
    }
}
