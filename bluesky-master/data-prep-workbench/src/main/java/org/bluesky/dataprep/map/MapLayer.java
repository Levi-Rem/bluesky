package org.bluesky.dataprep.map;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 地图五类图层之一：类别 + 计数 + GeoJSON 要素摘要（对应用户数据结构 MapLayerItem/MapFeatureSummary）。 */
public class MapLayer {

    private final String category;
    private final String name;
    private final List<Map<String, Object>> features = new ArrayList<>();

    public MapLayer(String category, String name) {
        this.category = category;
        this.name = name;
    }

    public String getCategory() {
        return category;
    }

    public String getName() {
        return name;
    }

    public int getCount() {
        return features.size();
    }

    public List<Map<String, Object>> getFeatures() {
        return features;
    }

    public void addFeature(String featureId, String entityId, String entityType,
                           String code, String name, int revision, Object geometry) {
        Map<String, Object> feature = new LinkedHashMap<>();
        feature.put("featureId", featureId);
        feature.put("entityId", entityId);
        feature.put("entityType", entityType);
        feature.put("code", code);
        feature.put("name", name);
        feature.put("revision", revision);
        feature.put("geometry", geometry);
        features.add(feature);
    }
}
