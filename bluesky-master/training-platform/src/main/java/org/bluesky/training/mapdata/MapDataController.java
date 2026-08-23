package org.bluesky.training.mapdata;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/workstation/map-layers")
public class MapDataController {
    private final MapDataService service;

    public MapDataController(MapDataService service) {
        this.service = service;
    }

    @GetMapping
    public MapLayersResponse snapshot() {
        return service.snapshot();
    }
}
