package org.bluesky.training.instruction;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.bluesky.training.adapter.SimulationGateway;
import org.bluesky.training.event.EventStreamService;
import org.bluesky.training.mapdata.ReferenceDataException;
import org.bluesky.training.mapdata.ReferenceDataResolver;
import org.bluesky.training.persistence.AircraftRow;
import org.bluesky.training.persistence.InstructionMapper;
import org.bluesky.training.persistence.InstructionRow;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InstructionProgressServiceTest {
    @Test
    void keepsQueuedPointInstructionPendingWhileReferenceDataIsNotReady() throws Exception {
        InstructionMapper mapper = mock(InstructionMapper.class);
        SimulationGateway gateway = mock(SimulationGateway.class);
        ReferenceDataResolver resolver = mock(ReferenceDataResolver.class);
        ObjectMapper objectMapper = new ObjectMapper();
        AircraftRow aircraft = new AircraftRow();
        aircraft.setId("aircraft-1");
        InstructionRow pending = new InstructionRow();
        pending.setId("instruction-1");
        pending.setExerciseAircraftId("aircraft-1");
        pending.setStatus("PENDING");
        pending.setControlChannel("LATERAL");
        pending.setParsedPayload(objectMapper.writeValueAsString(new EngineInstructionCommand(
                "CCA1", "DCT", null, null, null, null, null, "PUD", Collections.emptyList())));
        when(mapper.findNextPending("aircraft-1", "LATERAL")).thenReturn(pending);
        when(resolver.resolve(any(EngineInstructionCommand.class))).thenThrow(
                new ReferenceDataException("REFERENCE_DATA_NOT_READY", "等待同步"));
        InstructionProgressService service = new InstructionProgressService(
                mapper, gateway, objectMapper, mock(EventStreamService.class), resolver);

        service.evaluate(aircraft, objectMapper.createObjectNode());

        verify(gateway, never()).executeInstruction(any());
        verify(mapper, never()).updateStatus("instruction-1", "EXECUTING");
        verify(mapper, never()).markFailed(any(), any(), any());
    }
}
