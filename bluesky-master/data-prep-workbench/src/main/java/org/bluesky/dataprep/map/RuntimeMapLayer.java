package org.bluesky.dataprep.map;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read-only map layer consumed by the pseudo-pilot workstation. */
public class RuntimeMapLayer {
    private final String category;
    private final String name;
    private final List<Map<String, Object>> features = new ArrayList<>();

    public RuntimeMapLayer(String category, String name) {
        this.category = category;
        this.name = name;
    }

    public String getCategory() { return category; }
    public String getName() { return name; }
    public int getCount() { return features.size(); }
    public List<Map<String, Object>> getFeatures() { return features; }

    public void addFeature(String featureId, String featureType, String code,
                           String name, Object geometry) {
        Map<String, Object> feature = new LinkedHashMap<>();
        feature.put("featureId", featureId);
        feature.put("featureType", featureType);
        feature.put("code", code);
        feature.put("name", name);
        feature.put("geometry", geometry);
        features.add(feature);
    }
}
