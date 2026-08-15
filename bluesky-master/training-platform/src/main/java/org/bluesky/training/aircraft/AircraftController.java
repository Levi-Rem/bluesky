package org.bluesky.training.aircraft;

import org.bluesky.training.event.EventStreamService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/exercise-groups/{groupId}/aircraft")
public class AircraftController {
    private final AircraftService aircraftService;
    private final EventStreamService eventStreamService;

    public AircraftController(AircraftService aircraftService, EventStreamService eventStreamService) {
        this.aircraftService = aircraftService;
        this.eventStreamService = eventStreamService;
    }

    @GetMapping
    public List<AircraftResponse> list(@PathVariable String groupId) { return aircraftService.list(groupId); }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AircraftResponse create(@PathVariable String groupId, @RequestBody CreateAircraftRequest request) {
        AircraftResponse response = aircraftService.create(groupId, request);
        eventStreamService.publishAfterCommit("aircraft-upserted", response);
        return response;
    }
}
