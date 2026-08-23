package org.bluesky.training.mapdata;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class HttpMapDataClient implements MapDataClient {
    private static final Set<String> EXPECTED_CATEGORIES = new HashSet<>(
            Arrays.asList("WAYPOINT", "AIRWAY", "PHYSICAL_SECTOR", "WEATHER"));

    private final RestTemplate restTemplate;
    private final String runtimeLayersUrl;

    @Autowired
    public HttpMapDataClient(RestTemplateBuilder builder,
            @Value("${bluesky.data-prep.base-url:http://127.0.0.1:8090}") String baseUrl,
            @Value("${bluesky.data-prep.connect-timeout-millis:1000}") long connectTimeoutMillis,
            @Value("${bluesky.data-prep.read-timeout-millis:2000}") long readTimeoutMillis) {
        this.restTemplate = builder
                .setConnectTimeout(Duration.ofMillis(connectTimeoutMillis))
                .setReadTimeout(Duration.ofMillis(readTimeoutMillis))
                .build();
        this.runtimeLayersUrl = trimTrailingSlash(baseUrl) + "/api/map/runtime-layers";
    }

    HttpMapDataClient(RestTemplate restTemplate, String baseUrl) {
        this.restTemplate = restTemplate;
        this.runtimeLayersUrl = trimTrailingSlash(baseUrl) + "/api/map/runtime-layers";
    }

    @Override
    public MapLayersResponse fetch() {
        RemoteSnapshot remote = restTemplate.getForObject(runtimeLayersUrl, RemoteSnapshot.class);
        validate(remote);
        return MapLayersResponse.available(remote.getRevision(), remote.getLayers());
    }

    void validate(RemoteSnapshot remote) {
        if (remote == null || remote.getRevision() == null || remote.getLayers() == null
                || remote.getLayers().size() != EXPECTED_CATEGORIES.size()) {
            throw new IllegalStateException("数据准备地图快照结构不完整");
        }
        Set<String> categories = new HashSet<>();
        Set<String> featureIds = new HashSet<>();
        for (Map<String, Object> layer : remote.getLayers()) {
            Object category = layer == null ? null : layer.get("category");
            if (category == null || !EXPECTED_CATEGORIES.contains(String.valueOf(category))) {
                throw new IllegalStateException("数据准备地图快照包含未知分类");
            }
            String categoryName = String.valueOf(category);
            categories.add(categoryName);
            validateLayer(categoryName, layer, featureIds);
        }
        if (!categories.equals(EXPECTED_CATEGORIES)) {
            throw new IllegalStateException("数据准备地图快照分类不完整");
        }
    }

    @SuppressWarnings("unchecked")
    private void validateLayer(String category, Map<String, Object> layer, Set<String> featureIds) {
        if (!nonBlank(layer.get("name")) || !(layer.get("count") instanceof Number)
                || !(layer.get("features") instanceof List)) {
            throw new IllegalStateException("数据准备地图图层结构不完整: " + category);
        }
        List<Object> features = (List<Object>) layer.get("features");
        if (((Number) layer.get("count")).intValue() != features.size()) {
            throw new IllegalStateException("数据准备地图图层数量不一致: " + category);
        }
        for (Object value : features) {
            if (!(value instanceof Map)) {
                throw new IllegalStateException("数据准备地图要素结构不完整: " + category);
            }
            validateFeature(category, (Map<String, Object>) value, featureIds);
        }
    }

    @SuppressWarnings("unchecked")
    private void validateFeature(String category, Map<String, Object> feature,
                                 Set<String> featureIds) {
        String featureId = text(feature.get("featureId"));
        String featureType = text(feature.get("featureType"));
        if (featureId == null || featureType == null || !featureIds.add(featureId)
                || (!nonBlank(feature.get("code")) && !nonBlank(feature.get("name")))
                || !(feature.get("geometry") instanceof Map)) {
            throw new IllegalStateException("数据准备地图要素结构不完整: " + category);
        }
        Map<String, Object> geometry = (Map<String, Object>) feature.get("geometry");
        String geometryType = text(geometry.get("type"));
        Object coordinates = geometry.get("coordinates");
        boolean valid;
        if ("WAYPOINT".equals(category)) {
            valid = "WAYPOINT".equals(featureType) && "Point".equals(geometryType)
                    && validPoint(coordinates);
        } else if ("AIRWAY".equals(category)) {
            valid = "AIRWAY".equals(featureType) && "LineString".equals(geometryType)
                    && validLine(coordinates);
        } else if ("PHYSICAL_SECTOR".equals(category)) {
            valid = "PHYSICAL_SECTOR".equals(featureType) && "Polygon".equals(geometryType)
                    && validPolygon(coordinates);
        } else if ("WIND_FIELD_POINT".equals(featureType)) {
            valid = "WEATHER".equals(category) && "Point".equals(geometryType)
                    && validPoint(coordinates);
        } else {
            valid = "WEATHER".equals(category) && "SIGNIFICANT_WEATHER_AREA".equals(featureType)
                    && (("Polygon".equals(geometryType) && validPolygon(coordinates))
                    || ("MultiPolygon".equals(geometryType) && validMultiPolygon(coordinates)));
        }
        if (!valid) throw new IllegalStateException("数据准备地图要素几何不合法: " + featureId);
    }

    private boolean validPoint(Object value) {
        if (!(value instanceof List)) return false;
        List<?> coordinate = (List<?>) value;
        if (coordinate.size() < 2 || !(coordinate.get(0) instanceof Number)
                || !(coordinate.get(1) instanceof Number)) return false;
        double longitude = ((Number) coordinate.get(0)).doubleValue();
        double latitude = ((Number) coordinate.get(1)).doubleValue();
        return Double.isFinite(longitude) && Double.isFinite(latitude)
                && longitude >= -180d && longitude <= 180d
                && latitude >= -90d && latitude <= 90d;
    }

    private boolean validLine(Object value) {
        if (!(value instanceof List) || ((List<?>) value).size() < 2) return false;
        for (Object point : (List<?>) value) if (!validPoint(point)) return false;
        return true;
    }

    private boolean validPolygon(Object value) {
        if (!(value instanceof List) || ((List<?>) value).isEmpty()) return false;
        for (Object ringValue : (List<?>) value) {
            if (!(ringValue instanceof List)) return false;
            List<?> ring = (List<?>) ringValue;
            if (ring.size() < 4) return false;
            for (Object point : ring) if (!validPoint(point)) return false;
            if (!ring.get(0).equals(ring.get(ring.size() - 1))) return false;
        }
        return true;
    }

    private boolean validMultiPolygon(Object value) {
        if (!(value instanceof List) || ((List<?>) value).isEmpty()) return false;
        for (Object polygon : (List<?>) value) if (!validPolygon(polygon)) return false;
        return true;
    }

    private boolean nonBlank(Object value) {
        return value != null && !String.valueOf(value).trim().isEmpty();
    }

    private String text(Object value) {
        return nonBlank(value) ? String.valueOf(value) : null;
    }

    private String trimTrailingSlash(String value) {
        String result = value == null ? "" : value.trim();
        while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
        return result;
    }

    public static class RemoteSnapshot {
        private Long revision;
        private List<Map<String, Object>> layers;

        public Long getRevision() { return revision; }
        public void setRevision(Long revision) { this.revision = revision; }
        public List<Map<String, Object>> getLayers() { return layers; }
        public void setLayers(List<Map<String, Object>> layers) { this.layers = layers; }
    }
}
