package org.bluesky.training.aircraft;

import org.bluesky.training.event.EventStreamService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;

@RestController
@RequestMapping("/api/v1/aircraft")
public class AircraftDeleteController {
    private final AircraftService aircraftService;
    private final EventStreamService eventStreamService;

    public AircraftDeleteController(AircraftService aircraftService, EventStreamService eventStreamService) {
        this.aircraftService = aircraftService;
        this.eventStreamService = eventStreamService;
    }

    @DeleteMapping("/{aircraftId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String aircraftId) {
        if (aircraftService.delete(aircraftId)) {
            eventStreamService.publishAfterCommit("aircraft-deleted", Collections.singletonMap("id", aircraftId));
        }
    }
}
