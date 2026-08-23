package org.bluesky.training.mapdata;

import org.springframework.beans.factory.annotation.Value;
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

    @Override
    public MapLayersResponse fetch() {
        RemoteSnapshot remote = restTemplate.getForObject(runtimeLayersUrl, RemoteSnapshot.class);
        validate(remote);
        return MapLayersResponse.available(remote.getRevision(), remote.getLayers());
    }

    private void validate(RemoteSnapshot remote) {
        if (remote == null || remote.getRevision() == null || remote.getLayers() == null
                || remote.getLayers().size() != EXPECTED_CATEGORIES.size()) {
            throw new IllegalStateException("数据准备地图快照结构不完整");
        }
        Set<String> categories = new HashSet<>();
        for (Map<String, Object> layer : remote.getLayers()) {
            Object category = layer == null ? null : layer.get("category");
            if (category == null || !EXPECTED_CATEGORIES.contains(String.valueOf(category))) {
                throw new IllegalStateException("数据准备地图快照包含未知分类");
            }
            categories.add(String.valueOf(category));
        }
        if (!categories.equals(EXPECTED_CATEGORIES)) {
            throw new IllegalStateException("数据准备地图快照分类不完整");
        }
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
