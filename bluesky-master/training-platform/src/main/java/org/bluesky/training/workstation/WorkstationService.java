package org.bluesky.training.workstation;

import org.bluesky.training.adapter.EngineHealth;
import org.bluesky.training.adapter.SimulationGateway;
import org.bluesky.training.aircraft.AircraftService;
import org.bluesky.training.instruction.InstructionResponse;
import org.bluesky.training.persistence.BootstrapMapper;
import org.bluesky.training.persistence.ExerciseGroupRow;
import org.bluesky.training.persistence.InstructionMapper;
import org.bluesky.training.persistence.TerminalRow;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class WorkstationService {
    private final BootstrapMapper bootstrapMapper;
    private final SimulationGateway simulationGateway;
    private final AircraftService aircraftService;
    private final InstructionMapper instructionMapper;

    public WorkstationService(BootstrapMapper bootstrapMapper, SimulationGateway simulationGateway,
                              AircraftService aircraftService, InstructionMapper instructionMapper) {
        this.bootstrapMapper = bootstrapMapper;
        this.simulationGateway = simulationGateway;
        this.aircraftService = aircraftService;
        this.instructionMapper = instructionMapper;
    }

    public WorkstationBootstrapResponse bootstrap() {
        TerminalRow terminal = bootstrapMapper.findDefaultTerminal();
        ExerciseGroupRow group = bootstrapMapper.findDefaultGroup();
        EngineHealth engineHealth = simulationGateway.health();
        Map<String, String> parameters = parametersByKey(bootstrapMapper.findUiParameters());

        return new WorkstationBootstrapResponse(
                new WorkstationBootstrapResponse.TerminalView(terminal.getId(), terminal.getName()),
                new WorkstationBootstrapResponse.ExerciseGroupView(
                        group.getId(), group.getName(), group.getState(), group.getSimulationTimeSeconds()),
                engineHealth,
                new WorkstationBootstrapResponse.UiParametersView(
                        parameters.get("ui.theme"),
                        parameters.get("ui.trackColor"),
                        parameters.get("ui.selectedTrackColor")),
                aircraftService.list("GROUP-DEFAULT"),
                instructionMapper.findAllDefaultGroup().stream()
                        .map(InstructionResponse::new)
                        .collect(java.util.stream.Collectors.toList()));
    }

    private Map<String, String> parametersByKey(List<Map<String, String>> rows) {
        Map<String, String> result = new HashMap<>();
        for (Map<String, String> row : rows) {
            String key = value(row, "parameter_key", "PARAMETER_KEY");
            String value = value(row, "parameter_value", "PARAMETER_VALUE");
            result.put(key, value);
        }
        return result;
    }

    private String value(Map<String, String> row, String lowerCaseKey, String upperCaseKey) {
        String value = row.get(lowerCaseKey);
        return value != null ? value : row.get(upperCaseKey);
    }
}
