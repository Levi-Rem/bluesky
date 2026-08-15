package org.bluesky.training.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.bluesky.training.aircraft.AircraftResponse;
import org.bluesky.training.event.EventStreamService;
import org.bluesky.training.exercise.ExerciseGroupResponse;
import org.bluesky.training.instruction.InstructionProgressService;
import org.bluesky.training.persistence.AircraftMapper;
import org.bluesky.training.persistence.AircraftRow;
import org.bluesky.training.persistence.BootstrapMapper;
import org.bluesky.training.persistence.ExerciseGroupRow;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class AdapterStateProjector {
    private static final String PROTOCOL_VERSION = "1.0";
    private static final String DEFAULT_GROUP_ID = "GROUP-DEFAULT";

    private final ObjectMapper objectMapper;
    private final AircraftMapper aircraftMapper;
    private final BootstrapMapper bootstrapMapper;
    private final EventStreamService eventStreamService;
    private final AtomicLong lastSequence = new AtomicLong(-1L);
    private final AtomicReference<String> lastInstanceId = new AtomicReference<>("");
    private final Set<String> retiredInstanceIds = new HashSet<>();
    private final InstructionProgressService instructionProgressService;
    private final TransactionTemplate transactionTemplate;

    public AdapterStateProjector(ObjectMapper objectMapper, AircraftMapper aircraftMapper,
                                 BootstrapMapper bootstrapMapper, EventStreamService eventStreamService,
                                 InstructionProgressService instructionProgressService,
                                 PlatformTransactionManager transactionManager) {
        this.objectMapper = objectMapper;
        this.aircraftMapper = aircraftMapper;
        this.bootstrapMapper = bootstrapMapper;
        this.eventStreamService = eventStreamService;
        this.instructionProgressService = instructionProgressService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public synchronized void acceptJson(String json) {
        JsonNode frame;
        try {
            frame = objectMapper.readTree(json);
        } catch (IOException exception) {
            throw new IllegalArgumentException("Adapter 状态 JSON 无效", exception);
        }
        if (!PROTOCOL_VERSION.equals(frame.path("protocolVersion").asText())) {
            return;
        }
        if (!DEFAULT_GROUP_ID.equals(frame.path("exerciseGroupId").asText())) {
            return;
        }
        String instanceId = frame.path("instanceId").asText("legacy");
        long sequence = frame.path("sequence").asLong(-1L);
        if (!isNewSequence(instanceId, sequence)) {
            return;
        }

        Projection projection = transactionTemplate.execute(status -> persist(frame));
        lastInstanceId.set(instanceId);
        lastSequence.set(sequence);
        if (projection == null) return;
        eventStreamService.publish("exercise-state", projection.exerciseGroup);
        for (ProjectedAircraft aircraft : projection.aircraft) {
            eventStreamService.publish("aircraft-upserted", aircraft.response);
            instructionProgressService.evaluate(aircraft.existing, aircraft.state);
        }
    }

    private Projection persist(JsonNode frame) {
        ExerciseGroupRow group = bootstrapMapper.findDefaultGroup();
        if ("RUNNING".equals(group.getState())) {
            bootstrapMapper.updateSimulationTime(Math.max(0L,
                    Math.round(frame.path("simulationTimeSeconds").asDouble(0.0))));
            group = bootstrapMapper.findDefaultGroup();
        }
        Projection projection = new Projection(
                new ExerciseGroupResponse(group));
        for (JsonNode state : frame.path("aircraft")) {
            ProjectedAircraft aircraft = projectAircraft(state);
            if (aircraft != null) projection.aircraft.add(aircraft);
        }
        return projection;
    }

    private boolean isNewSequence(String instanceId, long sequence) {
        if (!instanceId.equals(lastInstanceId.get())) {
            if (retiredInstanceIds.contains(instanceId)) {
                return false;
            }
            String previous = lastInstanceId.get();
            if (!previous.isEmpty()) {
                retiredInstanceIds.add(previous);
            }
            if (sequence < 0L) {
                lastInstanceId.set(instanceId);
                lastSequence.set(-1L);
                return false;
            }
            return sequence >= 0L;
        }
        if (retiredInstanceIds.contains(instanceId)) {
            return false;
        }
        return sequence > lastSequence.get();
    }

    private ProjectedAircraft projectAircraft(JsonNode state) {
        String callsign = state.path("callsign").asText();
        AircraftRow existing = aircraftMapper.findByCallsign(callsign);
        if (existing == null) return null;

        AircraftRow update = new AircraftRow();
        update.setCallsign(callsign);
        update.setLatitude(state.path("latitude").asDouble());
        update.setLongitude(state.path("longitude").asDouble());
        update.setHeadingDegrees(state.path("headingDegrees").asDouble());
        update.setAltitudeFeet(state.path("altitudeFeet").asDouble());
        update.setSpeedKnots(state.path("speedKnots").asDouble());
        update.setVerticalSpeedFeetPerMinute(state.path("verticalSpeedFeetPerMinute").asDouble());
        List<String> route = new ArrayList<>();
        state.path("route").forEach(node -> route.add(node.asText()));
        update.setRouteText(String.join(" ", route));
        aircraftMapper.updateActualState(update);
        return new ProjectedAircraft(
                existing,
                state,
                new AircraftResponse(aircraftMapper.findByCallsign(callsign)));
    }

    private static final class Projection {
        private final ExerciseGroupResponse exerciseGroup;
        private final List<ProjectedAircraft> aircraft = new ArrayList<>();

        private Projection(ExerciseGroupResponse exerciseGroup) {
            this.exerciseGroup = exerciseGroup;
        }
    }

    private static final class ProjectedAircraft {
        private final AircraftRow existing;
        private final JsonNode state;
        private final AircraftResponse response;

        private ProjectedAircraft(AircraftRow existing, JsonNode state, AircraftResponse response) {
            this.existing = existing;
            this.state = state;
            this.response = response;
        }
    }
}
