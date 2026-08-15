package org.bluesky.training.reference;

import org.bluesky.training.adapter.SimulationGateway;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

@Service
public class ReferenceService {
    private static final int DEFAULT_LIMIT = 20;

    private final SimulationGateway simulationGateway;

    public ReferenceService(SimulationGateway simulationGateway) {
        this.simulationGateway = simulationGateway;
    }

    public List<ReferenceItem> search(String kind, String query) {
        return simulationGateway.searchReference(
                kind,
                query == null ? "" : query.trim().toUpperCase(Locale.ROOT),
                DEFAULT_LIMIT);
    }
}
