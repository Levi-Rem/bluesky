package org.bluesky.dataprep.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Strict validation for polygonal GeoJSON used by airspace and weather regions. */
public final class GeoJsonValidator {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private GeoJsonValidator() {
    }

    public static String validatePolygonGeometry(String value, String label) {
        if (value == null || value.trim().isEmpty()) throw ApiException.badRequest(label + "必填");
        try {
            JsonNode root = MAPPER.readTree(value);
            if (root == null || !root.isObject()) throw ApiException.badRequest(label + "必须为 GeoJSON 对象");
            String type = root.path("type").asText();
            JsonNode coordinates = root.get("coordinates");
            if ("Polygon".equals(type)) {
                validatePolygon(coordinates, label);
            } else if ("MultiPolygon".equals(type)) {
                if (coordinates == null || !coordinates.isArray() || coordinates.size() == 0) {
                    throw ApiException.badRequest(label + "的 MultiPolygon 不能为空");
                }
                for (JsonNode polygon : coordinates) validatePolygon(polygon, label);
            } else {
                throw ApiException.badRequest(label + "仅允许 Polygon / MultiPolygon");
            }
            return MAPPER.writeValueAsString(root);
        } catch (ApiException ex) {
            throw ex;
        } catch (Exception ex) {
            throw ApiException.badRequest(label + "不是合法 GeoJSON");
        }
    }

    private static void validatePolygon(JsonNode rings, String label) {
        if (rings == null || !rings.isArray() || rings.size() == 0) {
            throw ApiException.badRequest(label + "的 Polygon 不能为空");
        }
        for (JsonNode ring : rings) {
            if (!ring.isArray() || ring.size() < 4) throw ApiException.badRequest(label + "的边界环至少需要四个坐标");
            for (JsonNode coordinate : ring) validateCoordinate(coordinate, label);
            if (!ring.get(0).equals(ring.get(ring.size() - 1))) {
                throw ApiException.badRequest(label + "的边界环必须闭合");
            }
        }
    }

    private static void validateCoordinate(JsonNode coordinate, String label) {
        if (!coordinate.isArray() || coordinate.size() < 2
                || !coordinate.get(0).isNumber() || !coordinate.get(1).isNumber()) {
            throw ApiException.badRequest(label + "坐标格式不正确");
        }
        double longitude = coordinate.get(0).doubleValue();
        double latitude = coordinate.get(1).doubleValue();
        if (!Double.isFinite(longitude) || !Double.isFinite(latitude)
                || longitude < -180 || longitude > 180 || latitude < -90 || latitude > 90) {
            throw ApiException.badRequest(label + "经纬度超出范围");
        }
    }
}
