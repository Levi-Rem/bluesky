package org.bluesky.training.instruction;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.bluesky.training.adapter.SimulationGateway;
import org.bluesky.training.adapter.AdapterRejectedException;
import org.bluesky.training.adapter.AdapterUnavailableException;
import org.bluesky.training.configuration.FieldValidationException;
import org.bluesky.training.event.EventStreamService;
import org.bluesky.training.persistence.AircraftMapper;
import org.bluesky.training.persistence.AircraftRow;
import org.bluesky.training.persistence.InstructionMapper;
import org.bluesky.training.persistence.InstructionRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class InstructionService {
    private final AircraftMapper aircraftMapper;
    private final InstructionMapper instructionMapper;
    private final InstructionParser parser;
    private final SimulationGateway simulationGateway;
    private final ObjectMapper objectMapper;
    private final EventStreamService eventStreamService;

    public InstructionService(AircraftMapper aircraftMapper, InstructionMapper instructionMapper,
                              InstructionParser parser, SimulationGateway simulationGateway,
                              ObjectMapper objectMapper, EventStreamService eventStreamService) {
        this.aircraftMapper = aircraftMapper;
        this.instructionMapper = instructionMapper;
        this.parser = parser;
        this.simulationGateway = simulationGateway;
        this.objectMapper = objectMapper;
        this.eventStreamService = eventStreamService;
    }

    @Transactional(noRollbackFor = {AdapterRejectedException.class, AdapterUnavailableException.class})
    public InstructionResponse create(String aircraftId, CreateInstructionRequest request) {
        AircraftRow aircraft = aircraftMapper.findById(aircraftId);
        if (aircraft == null) throw new FieldValidationException("aircraftId", "航空器不存在");
        String insertion = normalizeInsertion(request.getInsertion());
        InstructionParser.ParsedInstruction parsed = parser.parse(
                request.getText(), aircraft.getCallsign(), aircraft.getAltitudeFeet());
        String channel = controlChannel(parsed.getCommand().getType());
        InstructionRow interrupted = null;
        boolean immediate = "IMMEDIATE".equals(insertion);
        if (immediate) {
            interrupted = instructionMapper.findExecuting(aircraftId, channel);
        }
        List<InstructionRow> pendingToCancel = immediate
                ? instructionMapper.findPending(aircraftId, channel)
                : java.util.Collections.emptyList();
        boolean executeNow = immediate || instructionMapper.executingCount(aircraftId, channel) == 0;

        InstructionRow row = new InstructionRow();
        row.setId(UUID.randomUUID().toString());
        EngineInstructionCommand command = parsed.getCommand().withCommandId(row.getId());
        row.setExerciseAircraftId(aircraftId);
        row.setRawText(parsed.getNormalizedText());
        row.setInstructionType(parsed.getCommand().getType());
        row.setControlChannel(channel);
        row.setInsertionMode(insertion);
        row.setStatus(executeNow ? "EXECUTING" : "PENDING");
        boolean shiftAfterDispatch = immediate && interrupted != null;
        row.setSequenceNumber(nextSequence(
                aircraftId, channel, insertion, executeNow, interrupted, shiftAfterDispatch));
        try {
            row.setParsedPayload(objectMapper.writeValueAsString(command));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("指令序列化失败", exception);
        }
        if (executeNow) {
            try {
                simulationGateway.executeInstruction(command);
            } catch (AdapterRejectedException | AdapterUnavailableException exception) {
                row.setStatus("FAILED");
                row.setFailureCode(failureCode(exception));
                row.setFailureMessage(exception.getMessage());
                row.setSequenceNumber(instructionMapper.maxSequence(aircraftId) + 1L);
                instructionMapper.insert(row);
                eventStreamService.publishAfterCommit(
                        "instruction-upserted", new InstructionResponse(row));
                throw exception;
            }
        }
        if (shiftAfterDispatch) {
            instructionMapper.shiftFrom(aircraftId, channel, row.getSequenceNumber());
        }
        if (immediate) {
            if (interrupted != null) {
                instructionMapper.cancelExecuting(aircraftId, channel);
                interrupted.setStatus("CANCELLED");
                eventStreamService.publishAfterCommit(
                        "instruction-upserted", new InstructionResponse(interrupted));
            }
            instructionMapper.cancelPending(aircraftId, channel);
            for (InstructionRow pending : pendingToCancel) {
                pending.setStatus("CANCELLED");
                eventStreamService.publishAfterCommit(
                        "instruction-upserted", new InstructionResponse(pending));
            }
        }
        instructionMapper.insert(row);
        if (executeNow) instructionMapper.updateActiveInstruction(aircraftId, row.getRawText());
        InstructionResponse response = new InstructionResponse(row);
        eventStreamService.publishAfterCommit("instruction-upserted", response);
        return response;
    }

    public List<InstructionResponse> list(String aircraftId) {
        if (aircraftMapper.findById(aircraftId) == null) {
            throw new FieldValidationException("aircraftId", "航空器不存在");
        }
        return instructionMapper.findAll(aircraftId).stream()
                .map(InstructionResponse::new)
                .collect(Collectors.toList());
    }

    private long nextSequence(String aircraftId, String channel, String insertion, boolean executeNow,
                              InstructionRow interrupted, boolean shiftAfterDispatch) {
        if ("IMMEDIATE".equals(insertion) && interrupted != null) {
            return interrupted.getSequenceNumber() + 1L;
        }
        if (executeNow || "APPEND".equals(insertion)) {
            return instructionMapper.maxSequence(aircraftId) + 1L;
        }
        InstructionRow executing = instructionMapper.findExecuting(aircraftId, channel);
        long sequence = executing == null ? 1L : executing.getSequenceNumber() + 1L;
        instructionMapper.shiftFrom(aircraftId, channel, sequence);
        return sequence;
    }

    private String controlChannel(String type) {
        if ("ALT".equals(type)) return "VERTICAL";
        if ("SPD".equals(type) || "MACH".equals(type)) return "SPEED";
        return "LATERAL";
    }

    private String failureCode(RuntimeException exception) {
        return exception instanceof AdapterRejectedException
                ? ((AdapterRejectedException) exception).getCode()
                : "ENGINE_UNAVAILABLE";
    }

    private String normalizeInsertion(String insertion) {
        String value = insertion == null ? "AFTER_CURRENT" : insertion.trim().toUpperCase(Locale.ROOT);
        if (!value.equals("AFTER_CURRENT") && !value.equals("IMMEDIATE") && !value.equals("APPEND")) {
            throw new FieldValidationException("insertion", "不支持的指令插入方式");
        }
        return value;
    }
}
