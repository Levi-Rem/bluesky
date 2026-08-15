package org.bluesky.training.adapter;

import org.bluesky.training.aircraft.AircraftCreateCommand;
import org.bluesky.training.instruction.EngineInstructionCommand;
import org.bluesky.training.reference.ReferenceItem;

import java.util.List;

public interface SimulationGateway {
    EngineHealth health();

    void start();

    void pause();

    void resume();

    void reset();

    void createAircraft(AircraftCreateCommand command);

    void deleteAircraft(String callsign);

    void executeInstruction(EngineInstructionCommand command);

    List<ReferenceItem> searchReference(String kind, String query, int limit);
}
