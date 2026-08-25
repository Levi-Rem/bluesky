package org.bluesky.training.reference;

import org.bluesky.training.adapter.SimulationGateway;
import org.bluesky.training.mapdata.MapDataService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

@Service
public class ReferenceService {
    private static final int DEFAULT_LIMIT = 20;

    private final SimulationGateway simulationGateway;
    private final MapDataService mapDataService;

    public ReferenceService(SimulationGateway simulationGateway, MapDataService mapDataService) {
        this.simulationGateway = simulationGateway;
        this.mapDataService = mapDataService;
    }

    public List<ReferenceItem> search(String kind, String query) {
        String normalizedKind = kind == null ? "" : kind.trim().toUpperCase(Locale.ROOT);
        String normalizedQuery = query == null ? "" : query.trim().toUpperCase(Locale.ROOT);
        if ("AIRPORT".equals(normalizedKind)) {
            return mapDataService.catalog().searchAirports(normalizedQuery, DEFAULT_LIMIT);
        }
        if ("WAYPOINT".equals(normalizedKind)) {
            return mapDataService.catalog().searchWaypoints(normalizedQuery, DEFAULT_LIMIT);
        }
        return simulationGateway.searchReference(normalizedKind, normalizedQuery, DEFAULT_LIMIT);
    }
}
