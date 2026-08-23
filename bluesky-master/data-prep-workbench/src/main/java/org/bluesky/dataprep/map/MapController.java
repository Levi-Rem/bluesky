package org.bluesky.dataprep.map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/map")
public class MapController {

    private final MapService mapService;
    private final RuntimeMapService runtimeMapService;

    public MapController(MapService mapService, RuntimeMapService runtimeMapService) {
        this.mapService = mapService;
        this.runtimeMapService = runtimeMapService;
    }

    @GetMapping("/layers")
    public Map<String, Object> layers() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("layers", mapService.layers());
        return body;
    }

    @GetMapping("/runtime-layers")
    public Map<String, Object> runtimeLayers() {
        return runtimeMapService.snapshot();
    }

    @PutMapping("/features")
    public Map<String, Object> apply(@RequestBody MapFeatureBatch batch) {
        return mapService.applyOperations(batch.getOperations());
    }

    public static class MapFeatureBatch {
        private List<MapFeatureOperation> operations;

        public List<MapFeatureOperation> getOperations() {
            return operations;
        }

        public void setOperations(List<MapFeatureOperation> operations) {
            this.operations = operations;
        }
    }
}
