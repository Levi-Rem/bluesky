package org.bluesky.dataprep.map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.bluesky.dataprep.airspace.AirspaceRow;
import org.bluesky.dataprep.airspace.AirspaceService;
import org.bluesky.dataprep.airway.AirwayRow;
import org.bluesky.dataprep.airway.AirwayService;
import org.bluesky.dataprep.common.ApiException;
import org.bluesky.dataprep.nav.NavPointRow;
import org.bluesky.dataprep.nav.NavPointService;
import org.bluesky.dataprep.radar.RadarService;
import org.bluesky.dataprep.radar.RadarSiteRow;
import org.bluesky.dataprep.weather.WeatherAreaRow;
import org.bluesky.dataprep.weather.WeatherAreaService;
import org.bluesky.dataprep.weather.WindFieldRow;
import org.bluesky.dataprep.weather.WindFieldService;
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
    private final AirwayService airwayService;
    private final WindFieldService windFieldService;
    private final WeatherAreaService weatherAreaService;
    private final RadarService radarService;

    public MapService(MapRefMapper refMapper, NavPointService navPointService,
                      AirspaceService airspaceService, AirwayService airwayService,
                      WindFieldService windFieldService, WeatherAreaService weatherAreaService,
                      RadarService radarService) {
        this.refMapper = refMapper;
        this.navPointService = navPointService;
        this.airspaceService = airspaceService;
        this.airwayService = airwayService;
        this.windFieldService = windFieldService;
        this.weatherAreaService = weatherAreaService;
        this.radarService = radarService;
    }

    public List<MapLayer> layers() {
        List<MapLayer> layers = new ArrayList<>();

        MapLayer navigation = new MapLayer("NAVIGATION", "导航数据");
        for (NavPointRow point : allNavigationPoints()) {
            navigation.addFeature("nav-" + point.getId(), point.getId(), "nav-point",
                    point.getCode(), point.getName(), point.getRevision(),
                    point(point.getLongitude(), point.getLatitude()));
        }
        layers.add(navigation);

        MapLayer airspaceLayer = new MapLayer("AIRSPACE", "空域数据");
        for (AirspaceRow airspace : allAirspaces()) {
            airspaceLayer.addFeature("as-" + airspace.getId(), airspace.getId(), "airspace",
                    airspace.getCode(), airspace.getName(), airspace.getRevision(), parse(airspace.getBoundary()));
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
                    String.valueOf(airway.get("code")), String.valueOf(airway.get("name")),
                    integer(airway.get("revision")), geometry);
        }
        layers.add(airwayLayer);

        MapLayer weatherLayer = new MapLayer("WEATHER", "气象数据");
        for (Map<String, Object> point : refMapper.selectWindPoints()) {
            weatherLayer.addFeature(String.valueOf(point.get("id")), String.valueOf(point.get("windFieldId")),
                    "wind-field", String.valueOf(point.get("code")), String.valueOf(point.get("name")),
                    integer(point.get("revision")),
                    point(dbl(point.get("longitude")), dbl(point.get("latitude"))));
        }
        for (Map<String, Object> area : refMapper.selectSigWeatherAreas()) {
            weatherLayer.addFeature("sw-" + area.get("id"), String.valueOf(area.get("id")),
                    "sig-weather", String.valueOf(area.get("code")), String.valueOf(area.get("name")),
                    integer(area.get("revision")),
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
                    integer(site.get("revision")),
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
        Map<String, Integer> revisionCursor = new LinkedHashMap<>();
        for (MapFeatureOperation operation : operations) {
            String key = operation.getEntityType() + ":" + operation.getEntityId();
            Integer currentRevision = revisionCursor.get(key);
            if (currentRevision != null) {
                operation.setRevision(currentRevision);
            }
            switch (operation.getOperationType() == null ? "" : operation.getOperationType()) {
                case "CREATE":
                    saved += create(operation);
                    break;
                case "UPDATE_GEOMETRY":
                    saved += updateGeometry(operation);
                    revisionCursor.put(key, operation.getRevision() + 1);
                    break;
                case "UPDATE_PROPERTIES":
                    saved += updateProperties(operation);
                    revisionCursor.put(key, operation.getRevision() + 1);
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

    private int create(MapFeatureOperation operation) {
        Map<String, Object> properties = operation.getProperties();
        String code = requiredProperty(properties, "code");
        String name = requiredProperty(properties, "name");
        Map<String, Object> geometry = parse(operation.getGeometry());
        if ("nav-point".equals(operation.getEntityType())) {
            if (geometry == null || !"Point".equals(geometry.get("type"))) {
                throw ApiException.badRequest("导航点几何必须为 GeoJSON Point");
            }
            List<Double> coordinates = coords(geometry.get("coordinates"));
            NavPointRow row = new NavPointRow();
            row.setCode(code);
            row.setName(name);
            row.setPointType(stringProperty(properties, "pointType", "FIX"));
            row.setLongitude(coordinates.get(0));
            row.setLatitude(coordinates.get(1));
            navPointService.create(row);
            return 1;
        }
        if ("airspace".equals(operation.getEntityType())) {
            if (geometry == null || (!("Polygon".equals(geometry.get("type")))
                    && !("MultiPolygon".equals(geometry.get("type"))))) {
                throw ApiException.badRequest("空域几何必须为 GeoJSON Polygon 或 MultiPolygon");
            }
            AirspaceRow row = new AirspaceRow();
            row.setCode(code);
            row.setName(name);
            row.setAirspaceType(stringProperty(properties, "airspaceType", "TMA"));
            row.setBoundary(operation.getGeometry());
            String lowerLimit = stringProperty(properties, "lowerLimit", "S0000");
            String upperLimit = stringProperty(properties, "upperLimit", "S3000");
            double lowerValue = heightCodeValue(lowerLimit, "下限");
            double upperValue = heightCodeValue(upperLimit, "上限");
            if (lowerValue > upperValue) {
                throw ApiException.badRequest("空域下限不能高于上限");
            }
            row.setLowerValue(lowerValue);
            row.setLowerReference("S");
            row.setUpperValue(upperValue);
            row.setUpperReference("S");
            airspaceService.create(row);
            return 1;
        }
        throw ApiException.badRequest("一期地图仅支持新增导航点与空域：" + operation.getEntityType());
    }

    private int updateGeometry(MapFeatureOperation operation) {
        promoteEditable(operation.getEntityType(), operation.getEntityId());
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
        if ("airway".equals(operation.getEntityType())) {
            return updateAirwayGeometry(operation);
        }
        if ("wind-field".equals(operation.getEntityType())) {
            return updateWindPointGeometry(operation);
        }
        if ("sig-weather".equals(operation.getEntityType())) {
            Map<String, Object> geometry = requireAreaGeometry(operation.getGeometry(), "气象区域");
            WeatherAreaRow current = weatherAreaService.get(operation.getEntityId());
            current.setArea(write(geometry));
            current.setRevision(operation.getRevision());
            weatherAreaService.update(operation.getEntityId(), current);
            return 1;
        }
        if ("radar-site".equals(operation.getEntityType())) {
            RadarCoverage coverage = radarCoverage(operation.getGeometry());
            RadarSiteRow current = radarService.getSite(operation.getEntityId());
            current.setLongitude(coverage.longitude);
            current.setLatitude(coverage.latitude);
            current.setMaximumRangeNm(coverage.rangeNm);
            current.setRevision(operation.getRevision());
            radarService.updateSite(operation.getEntityId(), current);
            return 1;
        }
        throw ApiException.badRequest("不支持的地图几何编辑类型：" + operation.getEntityType());
    }

    private int updateProperties(MapFeatureOperation operation) {
        promoteEditable(operation.getEntityType(), operation.getEntityId());
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
        if ("airway".equals(operation.getEntityType())) {
            AirwayRow current = airwayService.get(operation.getEntityId());
            applyCodeAndName(properties, current);
            current.setRevision(operation.getRevision());
            current.setSegments(null);
            airwayService.update(operation.getEntityId(), current);
            return 1;
        }
        if ("wind-field".equals(operation.getEntityType())) {
            WindFieldRow current = windFieldService.get(operation.getEntityId());
            applyCodeAndName(properties, current);
            current.setRevision(operation.getRevision());
            current.setPoints(null);
            windFieldService.update(operation.getEntityId(), current);
            return 1;
        }
        if ("sig-weather".equals(operation.getEntityType())) {
            WeatherAreaRow current = weatherAreaService.get(operation.getEntityId());
            if (properties.containsKey("code")) current.setCode(String.valueOf(properties.get("code")));
            if (properties.containsKey("name")) current.setName(String.valueOf(properties.get("name")));
            current.setRevision(operation.getRevision());
            weatherAreaService.update(operation.getEntityId(), current);
            return 1;
        }
        if ("radar-site".equals(operation.getEntityType())) {
            RadarSiteRow current = radarService.getSite(operation.getEntityId());
            if (properties.containsKey("code")) current.setCode(String.valueOf(properties.get("code")));
            if (properties.containsKey("name")) current.setName(String.valueOf(properties.get("name")));
            current.setRevision(operation.getRevision());
            radarService.updateSite(operation.getEntityId(), current);
            return 1;
        }
        throw ApiException.badRequest("不支持的地图属性编辑类型：" + operation.getEntityType());
    }

    private void delete(MapFeatureOperation operation) {
        promoteEditable(operation.getEntityType(), operation.getEntityId());
        if ("nav-point".equals(operation.getEntityType())) {
            navPointService.delete(operation.getEntityId(), operation.getRevision());
        } else if ("airspace".equals(operation.getEntityType())) {
            airspaceService.delete(operation.getEntityId(), operation.getRevision());
        } else if ("airway".equals(operation.getEntityType())) {
            airwayService.delete(operation.getEntityId(), operation.getRevision());
        } else if ("wind-field".equals(operation.getEntityType())) {
            windFieldService.delete(operation.getEntityId(), operation.getRevision());
        } else if ("sig-weather".equals(operation.getEntityType())) {
            weatherAreaService.delete(operation.getEntityId(), operation.getRevision());
        } else if ("radar-site".equals(operation.getEntityType())) {
            radarService.deleteSite(operation.getEntityId(), operation.getRevision());
        } else {
            throw ApiException.badRequest("不支持删除的地图对象类型：" + operation.getEntityType());
        }
    }

    private int updateAirwayGeometry(MapFeatureOperation operation) {
        Map<String, Object> geometry = parse(operation.getGeometry());
        if (geometry == null || !"LineString".equals(geometry.get("type"))) {
            throw ApiException.badRequest("航路几何必须为 GeoJSON LineString");
        }
        List<List<Double>> coordinates = coordinateList(geometry.get("coordinates"));
        List<Map<String, Object>> vertices = new ArrayList<>();
        for (Map<String, Object> vertex : refMapper.selectAirwayVertices()) {
            if (operation.getEntityId().equals(String.valueOf(vertex.get("airwayId")))) {
                vertices.add(vertex);
            }
        }
        if (vertices.size() != coordinates.size()) {
            throw ApiException.badRequest("航路顶点数量不能改变；请在航路列表中调整组成点");
        }

        Map<String, List<Double>> pointPositions = new LinkedHashMap<>();
        for (int i = 0; i < vertices.size(); i++) {
            String pointId = String.valueOf(vertices.get(i).get("pointId"));
            List<Double> coordinate = coordinates.get(i);
            List<Double> previous = pointPositions.get(pointId);
            if (previous != null && !sameCoordinate(previous, coordinate)) {
                throw ApiException.badRequest("航路相邻航段的共享点必须保持在同一位置");
            }
            pointPositions.put(pointId, coordinate);
        }

        AirwayRow airway = airwayService.get(operation.getEntityId());
        airway.setRevision(operation.getRevision());
        airway.setSegments(null);
        airwayService.update(operation.getEntityId(), airway);
        for (Map.Entry<String, List<Double>> entry : pointPositions.entrySet()) {
            refMapper.promoteNavPoint(entry.getKey());
            NavPointRow point = navPointService.get(entry.getKey());
            point.setLongitude(entry.getValue().get(0));
            point.setLatitude(entry.getValue().get(1));
            navPointService.update(entry.getKey(), point);
        }
        return 1;
    }

    /** 地图编辑是授权维护入口：只读来源对象首次编辑时转为人工维护，保留来源引用。 */
    private void promoteEditable(String entityType, String entityId) {
        if ("nav-point".equals(entityType)) refMapper.promoteNavPoint(entityId);
        else if ("airspace".equals(entityType)) refMapper.promoteAirspace(entityId);
        else if ("airway".equals(entityType)) refMapper.promoteAirway(entityId);
        else if ("wind-field".equals(entityType)) refMapper.promoteWindField(entityId);
        else if ("sig-weather".equals(entityType)) refMapper.promoteWeatherArea(entityId);
        else if ("radar-site".equals(entityType)) refMapper.promoteRadarSite(entityId);
    }

    private int updateWindPointGeometry(MapFeatureOperation operation) {
        Map<String, Object> geometry = parse(operation.getGeometry());
        if (geometry == null || !"Point".equals(geometry.get("type"))) {
            throw ApiException.badRequest("风场点几何必须为 GeoJSON Point");
        }
        if (operation.getFeatureId() == null || operation.getFeatureId().trim().isEmpty()) {
            throw ApiException.badRequest("风场点缺少地图要素标识");
        }
        Map<String, Object> pointInfo = null;
        for (Map<String, Object> candidate : refMapper.selectWindPoints()) {
            if (operation.getFeatureId().equals(String.valueOf(candidate.get("id")))) {
                pointInfo = candidate;
                break;
            }
        }
        if (pointInfo == null) {
            throw ApiException.notFound("风场点不存在：" + operation.getFeatureId());
        }
        if (!operation.getEntityId().equals(String.valueOf(pointInfo.get("windFieldId")))) {
            throw ApiException.badRequest("风场点不属于指定风场：" + operation.getFeatureId());
        }
        List<Double> coordinate = coords(geometry.get("coordinates"));
        WindFieldRow field = windFieldService.get(operation.getEntityId());
        field.setRevision(operation.getRevision());
        field.setPoints(null);
        windFieldService.update(operation.getEntityId(), field);
        refMapper.updateWindPointGeometry(operation.getFeatureId(), coordinate.get(0), coordinate.get(1));
        return 1;
    }

    private void applyCodeAndName(Map<String, Object> properties, AirwayRow row) {
        if (properties.containsKey("code")) row.setCode(String.valueOf(properties.get("code")));
        if (properties.containsKey("name")) row.setName(String.valueOf(properties.get("name")));
    }

    private void applyCodeAndName(Map<String, Object> properties, WindFieldRow row) {
        if (properties.containsKey("code")) row.setCode(String.valueOf(properties.get("code")));
        if (properties.containsKey("name")) row.setName(String.valueOf(properties.get("name")));
    }

    private Map<String, Object> requireAreaGeometry(String geoJson, String label) {
        Map<String, Object> geometry = parse(geoJson);
        if (geometry == null || (!"Polygon".equals(geometry.get("type"))
                && !"MultiPolygon".equals(geometry.get("type")))) {
            throw ApiException.badRequest(label + "几何必须为 GeoJSON Polygon 或 MultiPolygon");
        }
        return geometry;
    }

    private String write(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw ApiException.badRequest("GeoJSON 序列化失败：" + ex.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private List<List<Double>> coordinateList(Object value) {
        if (!(value instanceof List)) {
            throw ApiException.badRequest("GeoJSON 坐标格式不正确");
        }
        List<List<Double>> result = new ArrayList<>();
        for (Object item : (List<Object>) value) {
            result.add(coords(item));
        }
        return result;
    }

    private boolean sameCoordinate(List<Double> left, List<Double> right) {
        return Math.abs(left.get(0) - right.get(0)) < 1e-7
                && Math.abs(left.get(1) - right.get(1)) < 1e-7;
    }

    @SuppressWarnings("unchecked")
    private RadarCoverage radarCoverage(String geoJson) {
        Map<String, Object> geometry = parse(geoJson);
        if (geometry == null || !"Polygon".equals(geometry.get("type"))) {
            throw ApiException.badRequest("雷达覆盖几何必须为 GeoJSON Polygon");
        }
        Object rawCoordinates = geometry.get("coordinates");
        if (!(rawCoordinates instanceof List) || ((List<?>) rawCoordinates).isEmpty()
                || !(((List<?>) rawCoordinates).get(0) instanceof List)) {
            throw ApiException.badRequest("雷达覆盖区域坐标格式不正确");
        }
        List<?> rawRing = (List<?>) ((List<?>) rawCoordinates).get(0);
        int size = rawRing.size();
        if (size > 1 && sameCoordinate(coords(rawRing.get(0)), coords(rawRing.get(size - 1)))) size--;
        if (size < 3) throw ApiException.badRequest("雷达覆盖区域至少需要三个顶点");
        double longitude = 0;
        double latitude = 0;
        List<List<Double>> ring = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            List<Double> coordinate = coords(rawRing.get(i));
            ring.add(coordinate);
            longitude += coordinate.get(0);
            latitude += coordinate.get(1);
        }
        longitude /= size;
        latitude /= size;
        double radiusDegrees = 0;
        for (List<Double> coordinate : ring) {
            radiusDegrees = Math.max(radiusDegrees, Math.hypot(
                    coordinate.get(0) - longitude, coordinate.get(1) - latitude));
        }
        return new RadarCoverage(round6(longitude), round6(latitude), round6(radiusDegrees * 60));
    }

    private static final class RadarCoverage {
        private final double longitude;
        private final double latitude;
        private final double rangeNm;

        private RadarCoverage(double longitude, double latitude, double rangeNm) {
            this.longitude = longitude;
            this.latitude = latitude;
            this.rangeNm = rangeNm;
        }
    }

    private List<NavPointRow> allNavigationPoints() {
        List<NavPointRow> result = new ArrayList<>();
        int page = 0;
        do {
            List<NavPointRow> batch = navPointService.list(page++, 200).getItems();
            result.addAll(batch);
            if (batch.size() < 200) {
                break;
            }
        } while (true);
        return result;
    }

    private List<AirspaceRow> allAirspaces() {
        List<AirspaceRow> result = new ArrayList<>();
        int page = 0;
        do {
            List<AirspaceRow> batch = airspaceService.list(page++, 200).getItems();
            result.addAll(batch);
            if (batch.size() < 200) {
                break;
            }
        } while (true);
        return result;
    }

    private String requiredProperty(Map<String, Object> properties, String name) {
        if (properties == null || properties.get(name) == null
                || String.valueOf(properties.get(name)).trim().isEmpty()) {
            throw ApiException.badRequest("新增对象属性必填：" + name);
        }
        return String.valueOf(properties.get(name)).trim();
    }

    private String stringProperty(Map<String, Object> properties, String name, String defaultValue) {
        if (properties == null || properties.get(name) == null
                || String.valueOf(properties.get(name)).trim().isEmpty()) {
            return defaultValue;
        }
        return String.valueOf(properties.get(name)).trim();
    }

    private double heightCodeValue(String value, String label) {
        String normalized = value == null ? "" : value.trim().toUpperCase();
        if (!normalized.matches("S\\d{4}")) {
            throw ApiException.badRequest(label + "必须使用 S 加四位数字，例如 S0000");
        }
        return Double.parseDouble(normalized.substring(1));
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

    private int integer(Object value) {
        return value == null ? 0 : ((Number) value).intValue();
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

    private List<Double> coords(Object coordinates) {
        if (!(coordinates instanceof List)) {
            throw ApiException.badRequest("GeoJSON 坐标格式不正确");
        }
        List<?> raw = (List<?>) coordinates;
        if (raw.size() < 2 || !(raw.get(0) instanceof Number) || !(raw.get(1) instanceof Number)) {
            throw ApiException.badRequest("GeoJSON 坐标格式不正确");
        }
        return coord(((Number) raw.get(0)).doubleValue(), ((Number) raw.get(1)).doubleValue());
    }

    @Mapper
    public interface MapRefMapper {

        @Update("UPDATE navigation_point SET source_type = 'MANUAL' WHERE id = #{id} AND source_type = 'BLUESKY'")
        int promoteNavPoint(String id);

        @Update("UPDATE airspace SET source_type = 'MANUAL' WHERE id = #{id} AND source_type = 'BLUESKY'")
        int promoteAirspace(String id);

        @Update("UPDATE airway SET source_type = 'MANUAL' WHERE id = #{id} AND source_type = 'BLUESKY'")
        int promoteAirway(String id);

        @Update("UPDATE wind_field SET source_type = 'MANUAL' WHERE id = #{id} AND source_type = 'BLUESKY'")
        int promoteWindField(String id);

        @Update("UPDATE significant_weather_area SET source_type = 'MANUAL' WHERE id = #{id} AND source_type = 'BLUESKY'")
        int promoteWeatherArea(String id);

        @Update("UPDATE logical_radar_site SET source_type = 'MANUAL' WHERE id = #{id} AND source_type = 'BLUESKY'")
        int promoteRadarSite(String id);

        @Select("SELECT id AS \"id\", code AS \"code\", name AS \"name\", revision AS \"revision\" FROM airway WHERE deleted = FALSE")
        List<Map<String, Object>> selectAirways();

        /** 航路折线顶点：每段贡献起点+终点，按段序、再按起/终排序。 */
        @Select("SELECT s.airway_id AS \"airwayId\", s.order_no * 2 AS \"seq\", sp.id AS \"pointId\", sp.longitude AS \"longitude\", sp.latitude AS \"latitude\" "
                + "FROM airway_segment s JOIN navigation_point sp ON sp.id = s.start_point_id "
                + "JOIN airway a ON a.id = s.airway_id "
                + "WHERE s.deleted = FALSE AND sp.deleted = FALSE AND a.deleted = FALSE "
                + "UNION ALL "
                + "SELECT s.airway_id, s.order_no * 2 + 1, ep.id, ep.longitude, ep.latitude "
                + "FROM airway_segment s JOIN navigation_point ep ON ep.id = s.end_point_id "
                + "JOIN airway a ON a.id = s.airway_id "
                + "WHERE s.deleted = FALSE AND ep.deleted = FALSE AND a.deleted = FALSE "
                + "ORDER BY 1, 2")
        List<Map<String, Object>> selectAirwayVertices();

        @Select("SELECT p.id AS \"id\", w.id AS \"windFieldId\", w.code AS \"code\", w.name AS \"name\", w.revision AS \"revision\", p.longitude AS \"longitude\", p.latitude AS \"latitude\" "
                + "FROM wind_field_point p JOIN wind_field w ON w.id = p.wind_field_id "
                + "WHERE p.deleted = FALSE AND w.deleted = FALSE")
        List<Map<String, Object>> selectWindPoints();

        @Update("UPDATE wind_field_point SET longitude = #{longitude}, latitude = #{latitude} "
                + "WHERE id = #{id} AND deleted = FALSE")
        int updateWindPointGeometry(@Param("id") String id, @Param("longitude") double longitude,
                                    @Param("latitude") double latitude);

        @Select("SELECT id AS \"id\", code AS \"code\", name AS \"name\", revision AS \"revision\", CAST(boundary AS VARCHAR(16384)) AS \"boundary\" "
                + "FROM significant_weather_area WHERE deleted = FALSE")
        List<Map<String, Object>> selectSigWeatherAreas();

        @Select("SELECT id AS \"id\", code AS \"code\", name AS \"name\", revision AS \"revision\", longitude AS \"longitude\", latitude AS \"latitude\", maximum_range_nm AS \"maximumRangeNm\" "
                + "FROM logical_radar_site WHERE deleted = FALSE")
        List<Map<String, Object>> selectRadarSites();
    }
}
