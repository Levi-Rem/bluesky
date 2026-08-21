package org.bluesky.dataprep.map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.bluesky.dataprep.airspace.AirspaceRow;
import org.bluesky.dataprep.airspace.AirspaceService;
import org.bluesky.dataprep.common.ApiException;
import org.bluesky.dataprep.nav.NavPointRow;
import org.bluesky.dataprep.nav.NavPointService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 地图聚合：五类图层（导航/空域/航路/气象/雷达）+ 批量编辑保存。 */
@Service
public class MapService {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final MapRefMapper refMapper;
    private final NavPointService navPointService;
    private final AirspaceService airspaceService;

    public MapService(MapRefMapper refMapper, NavPointService navPointService,
                      AirspaceService airspaceService) {
        this.refMapper = refMapper;
        this.navPointService = navPointService;
        this.airspaceService = airspaceService;
    }

    public List<MapLayer> layers() {
        List<MapLayer> layers = new ArrayList<>();

        MapLayer navigation = new MapLayer("NAVIGATION", "导航数据");
        for (NavPointRow point : navPointService.list(0, 200).getItems()) {
            navigation.addFeature("nav-" + point.getId(), point.getId(), "nav-point",
                    point.getCode(), point.getName(),
                    point(point.getLongitude(), point.getLatitude()));
        }
        layers.add(navigation);

        MapLayer airspaceLayer = new MapLayer("AIRSPACE", "空域数据");
        for (AirspaceRow airspace : airspaceService.list(0, 200).getItems()) {
            airspaceLayer.addFeature("as-" + airspace.getId(), airspace.getId(), "airspace",
                    airspace.getCode(), airspace.getName(), parse(airspace.getBoundary()));
        }
        layers.add(airspaceLayer);

        MapLayer airwayLayer = new MapLayer("AIRWAY", "航路信息");
        Map<String, Map<String, Object>> airwayIndex = new LinkedHashMap<>();
        for (Map<String, Object> airway : refMapper.selectAirways()) {
            airwayIndex.put(String.valueOf(airway.get("id")), airway);
        }
        Map<String, List<List<Double>>> airwayPaths = new LinkedHashMap<>();
        for (Map<String, Object> vertex : refMapper.selectAirwayVertices()) {
            airwayPaths.computeIfAbsent(String.valueOf(vertex.get("airwayId")), k -> new ArrayList<>())
                    .add(coord(((Number) vertex.get("longitude")).doubleValue(),
                            ((Number) vertex.get("latitude")).doubleValue()));
        }
        for (Map.Entry<String, Map<String, Object>> entry : airwayIndex.entrySet()) {
            Map<String, Object> airway = entry.getValue();
            Map<String, Object> geometry = new LinkedHashMap<>();
            geometry.put("type", "LineString");
            geometry.put("coordinates", airwayPaths.getOrDefault(entry.getKey(), new ArrayList<>()));
            airwayLayer.addFeature("aw-" + entry.getKey(), entry.getKey(), "airway",
                    String.valueOf(airway.get("code")), String.valueOf(airway.get("name")), geometry);
        }
        layers.add(airwayLayer);

        MapLayer weatherLayer = new MapLayer("WEATHER", "气象数据");
        for (Map<String, Object> point : refMapper.selectWindPoints()) {
            weatherLayer.addFeature("wp-" + point.get("id"), String.valueOf(point.get("windFieldId")),
                    "wind-field", String.valueOf(point.get("code")), String.valueOf(point.get("name")),
                    point(dbl(point.get("longitude")), dbl(point.get("latitude"))));
        }
        for (Map<String, Object> area : refMapper.selectSigWeatherAreas()) {
            weatherLayer.addFeature("sw-" + area.get("id"), String.valueOf(area.get("id")),
                    "sig-weather", String.valueOf(area.get("code")), String.valueOf(area.get("name")),
                    parse(String.valueOf(area.get("boundary"))));
        }
        layers.add(weatherLayer);

        MapLayer radarLayer = new MapLayer("RADAR", "雷达与通道");
        for (Map<String, Object> site : refMapper.selectRadarSites()) {
            Double lon = dbl(site.get("longitude"));
            Double lat = dbl(site.get("latitude"));
            Double rangeNm = site.get("maximumRangeNm") == null ? null
                    : ((Number) site.get("maximumRangeNm")).doubleValue();
            radarLayer.addFeature("rs-" + site.get("id"), String.valueOf(site.get("id")),
                    "radar-site", String.valueOf(site.get("code")), String.valueOf(site.get("name")),
                    coveragePolygon(lon, lat, rangeNm));
        }
        layers.add(radarLayer);

        return layers;
    }

    @Transactional
    public Map<String, Object> applyOperations(List<MapFeatureOperation> operations) {
        if (operations == null || operations.isEmpty()) {
            throw ApiException.badRequest("地图保存操作不能为空");
        }
        int saved = 0;
        for (MapFeatureOperation operation : operations) {
            switch (operation.getOperationType() == null ? "" : operation.getOperationType()) {
                case "UPDATE_GEOMETRY":
                    saved += updateGeometry(operation);
                    break;
                case "UPDATE_PROPERTIES":
                    saved += updateProperties(operation);
                    break;
                case "DELETE":
                    delete(operation);
                    saved++;
                    break;
                default:
                    throw ApiException.badRequest("不支持的操作类型：" + operation.getOperationType());
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("saved", saved);
        return result;
    }

    private int updateGeometry(MapFeatureOperation operation) {
        if ("nav-point".equals(operation.getEntityType())) {
            Map<String, Object> geometry = parse(operation.getGeometry());
            if (geometry == null || !"Point".equals(geometry.get("type"))) {
                throw ApiException.badRequest("导航点几何必须为 GeoJSON Point");
            }
            List<Double> coords = coords(geometry.get("coordinates"));
            NavPointRow current = navPointService.get(operation.getEntityId());
            current.setLongitude(coords.get(0));
            current.setLatitude(coords.get(1));
            current.setRevision(operation.getRevision());
            navPointService.update(operation.getEntityId(), current);
            return 1;
        }
        if ("airspace".equals(operation.getEntityType())) {
            AirspaceRow current = airspaceService.get(operation.getEntityId());
            current.setBoundary(operation.getGeometry());
            current.setRevision(operation.getRevision());
            airspaceService.update(operation.getEntityId(), current);
            return 1;
        }
        throw ApiException.badRequest("一期地图仅支持导航点与空域几何编辑：" + operation.getEntityType());
    }

    private int updateProperties(MapFeatureOperation operation) {
        Map<String, Object> properties = operation.getProperties();
        if (properties == null || properties.isEmpty()) {
            throw ApiException.badRequest("属性补丁不能为空");
        }
        if ("nav-point".equals(operation.getEntityType())) {
            NavPointRow current = navPointService.get(operation.getEntityId());
            if (properties.containsKey("code")) {
                current.setCode(String.valueOf(properties.get("code")));
            }
            if (properties.containsKey("name")) {
                current.setName(String.valueOf(properties.get("name")));
            }
            current.setRevision(operation.getRevision());
            navPointService.update(operation.getEntityId(), current);
            return 1;
        }
        if ("airspace".equals(operation.getEntityType())) {
            AirspaceRow current = airspaceService.get(operation.getEntityId());
            if (properties.containsKey("code")) {
                current.setCode(String.valueOf(properties.get("code")));
            }
            if (properties.containsKey("name")) {
                current.setName(String.valueOf(properties.get("name")));
            }
            current.setRevision(operation.getRevision());
            airspaceService.update(operation.getEntityId(), current);
            return 1;
        }
        throw ApiException.badRequest("一期地图仅支持导航点与空域属性编辑：" + operation.getEntityType());
    }

    private void delete(MapFeatureOperation operation) {
        if ("nav-point".equals(operation.getEntityType())) {
            navPointService.delete(operation.getEntityId(), operation.getRevision());
        } else if ("airspace".equals(operation.getEntityType())) {
            airspaceService.delete(operation.getEntityId(), operation.getRevision());
        } else {
            throw ApiException.badRequest("一期地图仅支持删除导航点与空域：" + operation.getEntityType());
        }
    }

    private Map<String, Object> point(Double lon, Double lat) {
        Map<String, Object> geometry = new LinkedHashMap<>();
        geometry.put("type", "Point");
        List<Double> coordinates = new ArrayList<>();
        coordinates.add(lon);
        coordinates.add(lat);
        geometry.put("coordinates", coordinates);
        return geometry;
    }

    /** 以站点为圆心、最大距离为半径的近似多边形（24 边）。 */
    private Map<String, Object> coveragePolygon(Double lon, Double lat, Double rangeNm) {
        double radiusDeg = rangeNm == null ? 0.5 : rangeNm / 60.0;
        List<List<Double>> ring = new ArrayList<>();
        for (int i = 0; i <= 24; i++) {
            double angle = 2 * Math.PI * i / 24;
            ring.add(coord(lon + radiusDeg * Math.cos(angle), lat + radiusDeg * Math.sin(angle)));
        }
        Map<String, Object> geometry = new LinkedHashMap<>();
        geometry.put("type", "Polygon");
        List<List<List<Double>>> polygons = new ArrayList<>();
        polygons.add(ring);
        geometry.put("coordinates", polygons);
        return geometry;
    }

    private List<Double> coord(double lon, double lat) {
        List<Double> pair = new ArrayList<>();
        pair.add(round6(lon));
        pair.add(round6(lat));
        return pair;
    }

    private Double dbl(Object value) {
        return value == null ? null : ((Number) value).doubleValue();
    }

    private double round6(double value) {
        return Math.round(value * 1e6) / 1e6;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parse(String geoJson) {
        if (geoJson == null || geoJson.trim().isEmpty()) {
            return null;
        }
        try {
            return objectMapper.readValue(geoJson, Map.class);
        } catch (Exception ex) {
            throw ApiException.badRequest("GeoJSON 解析失败：" + ex.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private List<Double> coords(Object coordinates) {
        return (List<Double>) coordinates;
    }

    @Mapper
    public interface MapRefMapper {

        @Select("SELECT id AS \"id\", code AS \"code\", name AS \"name\" FROM airway WHERE deleted = FALSE")
        List<Map<String, Object>> selectAirways();

        /** 航路折线顶点：每段贡献起点+终点，按段序、再按起/终排序。 */
        @Select("SELECT s.airway_id AS \"airwayId\", s.order_no * 2 AS \"seq\", sp.longitude AS \"longitude\", sp.latitude AS \"latitude\" "
                + "FROM airway_segment s JOIN navigation_point sp ON sp.id = s.start_point_id "
                + "WHERE s.deleted = FALSE "
                + "UNION ALL "
                + "SELECT s.airway_id, s.order_no * 2 + 1, ep.longitude, ep.latitude "
                + "FROM airway_segment s JOIN navigation_point ep ON ep.id = s.end_point_id "
                + "WHERE s.deleted = FALSE "
                + "ORDER BY 1, 2")
        List<Map<String, Object>> selectAirwayVertices();

        @Select("SELECT p.id AS \"id\", w.id AS \"windFieldId\", w.code AS \"code\", w.name AS \"name\", p.longitude AS \"longitude\", p.latitude AS \"latitude\" "
                + "FROM wind_field_point p JOIN wind_field w ON w.id = p.wind_field_id "
                + "WHERE p.deleted = FALSE AND w.deleted = FALSE")
        List<Map<String, Object>> selectWindPoints();

        @Select("SELECT id AS \"id\", code AS \"code\", name AS \"name\", CAST(boundary AS VARCHAR(16384)) AS \"boundary\" "
                + "FROM significant_weather_area WHERE deleted = FALSE")
        List<Map<String, Object>> selectSigWeatherAreas();

        @Select("SELECT id AS \"id\", code AS \"code\", name AS \"name\", longitude AS \"longitude\", latitude AS \"latitude\", maximum_range_nm AS \"maximumRangeNm\" "
                + "FROM logical_radar_site WHERE deleted = FALSE")
        List<Map<String, Object>> selectRadarSites();
    }
}
