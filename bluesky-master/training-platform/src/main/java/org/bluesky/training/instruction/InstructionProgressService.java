package org.bluesky.training.instruction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.bluesky.training.adapter.SimulationGateway;
import org.bluesky.training.adapter.AdapterUnavailableException;
import org.bluesky.training.adapter.AdapterRejectedException;
import org.bluesky.training.event.EventStreamService;
import org.bluesky.training.persistence.AircraftRow;
import org.bluesky.training.persistence.InstructionMapper;
import org.bluesky.training.persistence.InstructionRow;
import org.springframework.stereotype.Service;

import java.io.IOException;
@Service
public class InstructionProgressService {
    private final InstructionMapper instructionMapper;
    private final SimulationGateway simulationGateway;
    private final ObjectMapper objectMapper;
    private final EventStreamService eventStreamService;

    public InstructionProgressService(InstructionMapper instructionMapper,
                                      SimulationGateway simulationGateway,
                                      ObjectMapper objectMapper,
                                      EventStreamService eventStreamService) {
        this.instructionMapper = instructionMapper;
        this.simulationGateway = simulationGateway;
        this.objectMapper = objectMapper;
        this.eventStreamService = eventStreamService;
    }

    public void evaluate(AircraftRow aircraft, JsonNode actualState) {
        evaluateChannel(aircraft, actualState, "LATERAL");
        evaluateChannel(aircraft, actualState, "VERTICAL");
        evaluateChannel(aircraft, actualState, "SPEED");
    }

    private void evaluateChannel(AircraftRow aircraft, JsonNode actualState, String channel) {
        InstructionRow current = instructionMapper.findExecuting(aircraft.getId(), channel);
        if (current == null || !isComplete(current, actualState)) return;

        instructionMapper.updateStatus(current.getId(), "COMPLETED");
        current.setStatus("COMPLETED");
        eventStreamService.publishAfterCommit("instruction-upserted", new InstructionResponse(current));

        InstructionRow next = instructionMapper.findNextPending(aircraft.getId(), channel);
        if (next == null) {
            refreshActiveInstruction(aircraft.getId());
            return;
        }
        EngineInstructionCommand command = resolveForDispatch(
                parseCommand(next.getParsedPayload()), actualState);
        try {
            simulationGateway.executeInstruction(command);
        } catch (AdapterUnavailableException | AdapterRejectedException exception) {
            String failureCode = exception instanceof AdapterRejectedException
                    ? ((AdapterRejectedException) exception).getCode()
                    : "ENGINE_UNAVAILABLE";
            instructionMapper.markFailed(next.getId(), failureCode, exception.getMessage());
            next.setStatus("FAILED");
            next.setFailureCode(failureCode);
            next.setFailureMessage(exception.getMessage());
            refreshActiveInstruction(aircraft.getId());
            eventStreamService.publishAfterCommit("instruction-upserted", new InstructionResponse(next));
            return;
        }
        instructionMapper.updateStatus(next.getId(), "EXECUTING");
        next.setStatus("EXECUTING");
        instructionMapper.updateActiveInstruction(aircraft.getId(), next.getRawText());
        eventStreamService.publishAfterCommit("instruction-upserted", new InstructionResponse(next));
    }

    private void refreshActiveInstruction(String aircraftId) {
        InstructionRow remaining = instructionMapper.findLatestExecuting(aircraftId);
        instructionMapper.updateActiveInstruction(
                aircraftId, remaining == null ? null : remaining.getRawText());
    }

    private boolean isComplete(InstructionRow row, JsonNode actual) {
        EngineInstructionCommand command = parseCommand(row.getParsedPayload());
        switch (command.getType()) {
            case "HDG":
                double difference = Math.abs(actual.path("headingDegrees").asDouble()
                        - command.getHeadingDegrees());
                return Math.min(difference, 360.0 - difference) <= 2.0;
            case "ALT":
                return Math.abs(actual.path("altitudeFeet").asDouble()
                        - command.getAltitudeFeet()) <= 100.0;
            case "SPD":
                return Math.abs(actual.path("speedKnots").asDouble()
                        - command.getSpeedKnots()) <= 5.0;
            case "MACH":
                return Math.abs(actual.path("mach").asDouble()
                        - command.getMach()) <= 0.01;
            case "DCT":
                JsonNode directTo = actual.path("directTo");
                return command.getCommandId() != null
                        && command.getCommandId().equals(directTo.path("commandId").asText())
                        && command.getWaypoint().equals(directTo.path("waypoint").asText())
                        && directTo.path("passed").asBoolean(false);
            case "RTE":
                JsonNode routeChange = actual.path("routeChange");
                return command.getCommandId() != null
                        && command.getCommandId().equals(routeChange.path("commandId").asText())
                        && routeChange.path("activated").asBoolean(false);
            default:
                return false;
        }
    }

    private EngineInstructionCommand parseCommand(String json) {
        try {
            return objectMapper.readValue(json, EngineInstructionCommand.class);
        } catch (IOException exception) {
            throw new IllegalStateException("保存的指令参数无法解析", exception);
        }
    }

    private EngineInstructionCommand resolveForDispatch(
            EngineInstructionCommand command, JsonNode actualState) {
        if (!"ALT".equals(command.getType())
                || command.getVerticalSpeedFeetPerMinute() == null) {
            return command;
        }
        double magnitude = Math.abs(command.getVerticalSpeedFeetPerMinute());
        double actualAltitude = actualState.path("altitudeFeet").asDouble();
        double resolved = command.getAltitudeFeet() < actualAltitude ? -magnitude : magnitude;
        return command.withVerticalSpeedFeetPerMinute(resolved);
    }
}
