package org.bluesky.training.configuration;

import org.bluesky.training.adapter.EngineHealth;
import org.bluesky.training.adapter.AdapterUnavailableException;
import org.bluesky.training.adapter.SimulationGateway;
import org.bluesky.training.persistence.BootstrapMapper;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class StartupResetTest {
    @Test
    void resetsDatabaseAndConnectedAdapter() {
        BootstrapMapper mapper = mock(BootstrapMapper.class);
        SimulationGateway gateway = mock(SimulationGateway.class);
        when(gateway.health()).thenReturn(
                new EngineHealth(true, "CONNECTED", "OPENAP", "connected"));

        new StartupReset(mapper, gateway).run(null);

        org.mockito.InOrder order = inOrder(mapper, gateway);
        order.verify(mapper).deleteInstructions();
        order.verify(mapper).deleteAircraft();
        order.verify(mapper).resetDefaultGroup();
        order.verify(gateway).health();
        order.verify(gateway).reset();
    }

    @Test
    void doesNotBlockStartupWhenAdapterIsDisconnected() {
        BootstrapMapper mapper = mock(BootstrapMapper.class);
        SimulationGateway gateway = mock(SimulationGateway.class);
        when(gateway.health()).thenReturn(
                new EngineHealth(false, "DISCONNECTED", "UNKNOWN", "offline"));

        new StartupReset(mapper, gateway).run(null);

        verify(mapper).resetDefaultGroup();
        verify(gateway, never()).reset();
    }

    @Test
    void doesNotRollBackDatabaseResetWhenConnectedAdapterDropsDuringReset() {
        BootstrapMapper mapper = mock(BootstrapMapper.class);
        SimulationGateway gateway = mock(SimulationGateway.class);
        when(gateway.health()).thenReturn(
                new EngineHealth(true, "CONNECTED", "OPENAP", "connected"));
        org.mockito.Mockito.doThrow(new AdapterUnavailableException("connection dropped"))
                .when(gateway).reset();

        assertDoesNotThrow(() -> new StartupReset(mapper, gateway).run(null));

        verify(mapper).deleteInstructions();
        verify(mapper).deleteAircraft();
        verify(mapper).resetDefaultGroup();
        verify(gateway).reset();
    }
}
