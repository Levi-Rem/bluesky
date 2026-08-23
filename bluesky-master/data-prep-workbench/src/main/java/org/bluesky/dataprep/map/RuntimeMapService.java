package org.bluesky.dataprep.map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.bluesky.dataprep.common.RevisionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class RuntimeMapService {
    private static final Logger log = LoggerFactory.getLogger(RuntimeMapService.class);
    private static final TypeReference<Map<String, Object>> JSON_OBJECT =
            new TypeReference<Map<String, Object>>() { };

    private final RuntimeMapMapper mapper;
    private final RevisionService revisionService;
    private final ObjectMapper objectMapper;

    public RuntimeMapService(RuntimeMapMapper mapper, RevisionService revisionService,
                             ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.revisionService = revisionService;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> snapshot() {
        List<RuntimeMapLayer> layers = new ArrayList<>();
        layers.add(waypoints());
        layers.add(airways());
        layers.add(physicalSectors());
        layers.add(weather());
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("revision", revisionService.current());
        response.put("layers", layers);
        return response;
    }

    private RuntimeMapLayer waypoints() {
        RuntimeMapLayer layer = new RuntimeMapLayer("WAYPOINT", "航路点");
        for (Map<String, Object> row : mapper.selectWaypoints()) {
            String id = string(row.get("id"));
            try {
                layer.addFeature("waypoint:" + id, "WAYPOINT", string(row.get("code")),
                        string(row.get("name")), point(row.get("longitude"), row.get("latitude")));
            } catch (IllegalArgumentException error) {
                warn("WAYPOINT", id, error);
            }
        }
        return layer;
    }

    private RuntimeMapLayer airways() {
        RuntimeMapLayer layer = new RuntimeMapLayer("AIRWAY", "航线");
        CoordinateGroups paths = coordinatesBy(
                mapper.selectAirwayVertices(), "airwayId");
        for (Map<String, Object> row : mapper.selectAirways()) {
            String id = string(row.get("id"));
            try {
                rejectInvalidGroup(paths, id, "航线包含缺失或非法顶点");
                List<List<Double>> path = removeAdjacentDuplicates(paths.coordinates.get(id));
                if (path.size() < 2) throw new IllegalArgumentException("航线有效顶点少于两个");
                layer.addFeature("airway:" + id, "AIRWAY", string(row.get("code")),
                        string(row.get("name")), geometry("LineString", path));
            } catch (IllegalArgumentException error) {
                warn("AIRWAY", id, error);
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
