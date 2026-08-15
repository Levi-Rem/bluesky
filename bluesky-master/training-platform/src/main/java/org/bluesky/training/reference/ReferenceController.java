package org.bluesky.training.reference;

import org.bluesky.training.adapter.SimulationGateway;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;

@RestController
@RequestMapping("/api/v1/reference")
public class ReferenceController {
    private static final int DEFAULT_LIMIT = 20;

    private final SimulationGateway simulationGateway;

    public ReferenceController(SimulationGateway simulationGateway) {
        this.simulationGateway = simulationGateway;
    }

    @GetMapping("/airports")
    public List<ReferenceItem> airports(@RequestParam(defaultValue = "") String query) {
        return search("AIRPORT", query);
    }

    @GetMapping("/waypoints")
    public List<ReferenceItem> waypoints(@RequestParam(defaultValue = "") String query) {
        return search("WAYPOINT", query);
    }

    @GetMapping("/aircraft-types")
    public List<ReferenceItem> aircraftTypes(@RequestParam(defaultValue = "") String query) {
        return search("AIRCRAFT_TYPE", query);
    }

    private List<ReferenceItem> search(String kind, String query) {
        return simulationGateway.searchReference(
                kind,
                query == null ? "" : query.trim().toUpperCase(Locale.ROOT),
                DEFAULT_LIMIT);
    }
}
