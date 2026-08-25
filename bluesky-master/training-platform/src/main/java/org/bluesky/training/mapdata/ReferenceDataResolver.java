package org.bluesky.training.mapdata;

import org.bluesky.training.aircraft.AircraftCreateCommand;
import org.bluesky.training.instruction.EngineInstructionCommand;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

@Component
public class ReferenceDataResolver {
    private final MapDataService mapDataService;
    private final boolean enforcementEnabled;

    public ReferenceDataResolver(MapDataService mapDataService,
                                 @Value("${bluesky.reference-data.enforcement-enabled:true}")
                                 boolean enforcementEnabled) {
        this.mapDataService = mapDataService;
        this.enforcementEnabled = enforcementEnabled;
    }

    public void requireReady() {
        if (!enforcementEnabled) return;
        ReferenceDataState state = mapDataService.referenceDataState();
        if (!state.isReady()) {
            throw new ReferenceDataException("REFERENCE_DATA_NOT_READY", state.getMessage());
        }
    }

    public AircraftCreateCommand resolve(AircraftCreateCommand command) {
        if (!enforcementEnabled) return command;
        requireReady();
        RuntimeReferenceCatalog catalog = mapDataService.catalog();
        RuntimeNavigationPoint origin = requireAirport(catalog.resolve(command.getOrigin()), "起飞机场");
        RuntimeNavigationPoint destination = requireAirport(
                catalog.resolve(command.getDestination()), "落地机场");
        RuntimeNavigationPoint initial = command.getInitialWaypoint() == null
                ? null : catalog.resolve(command.getInitialWaypoint());
        List<RuntimeNavigationPoint> route = catalog.expandRoute(command.getRoute());
        if (route.isEmpty()) route = Collections.singletonList(destination);
        if (!route.get(route.size() - 1).getCode().equals(destination.getCode())) {
            throw new ReferenceDataException("ROUTE_DESTINATION_MISMATCH",
                    "航线最后一点必须是落地机场：" + destination.getCode());
        }
        return command.withResolvedPoints(origin, destination, initial, route);
    }

    public EngineInstructionCommand resolve(EngineInstructionCommand command) {
        if (!enforcementEnabled) return command;
        requireReady();
        RuntimeReferenceCatalog catalog = mapDataService.catalog();
        if ("DCT".equals(command.getType())) {
            return command.withResolvedPoints(catalog.resolve(command.getWaypoint()),
                    Collections.emptyList());
        }
        if ("RTE".equals(command.getType())) {
            return command.withResolvedPoints(null, catalog.expandRoute(command.getRoute()));
        }
        return command;
    }

    private RuntimeNavigationPoint requireAirport(RuntimeNavigationPoint point, String field) {
        if (!"AIRPORT".equals(point.getType())) {
            throw new ReferenceDataException("INVALID_AIRPORT_REFERENCE",
                    field + "不是机场：" + point.getCode());
        }
        return point;
    }
}
