package org.bluesky.training.configuration;

import org.bluesky.training.adapter.SimulationGateway;
import org.bluesky.training.adapter.EngineHealth;
import org.bluesky.training.adapter.AdapterUnavailableException;
import org.bluesky.training.persistence.BootstrapMapper;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class StartupReset implements ApplicationRunner {
    private final BootstrapMapper bootstrapMapper;
    private final SimulationGateway simulationGateway;

    public StartupReset(BootstrapMapper bootstrapMapper, SimulationGateway simulationGateway) {
        this.bootstrapMapper = bootstrapMapper;
        this.simulationGateway = simulationGateway;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        bootstrapMapper.deleteInstructions();
        bootstrapMapper.deleteAircraft();
        bootstrapMapper.resetDefaultGroup();
        EngineHealth health = simulationGateway.health();
        if (health != null && health.isConnected()) {
            try {
                simulationGateway.reset();
            } catch (AdapterUnavailableException ignored) {
                // The platform database reset remains authoritative.  Runtime
                // health monitoring reports the adapter disconnect to the UI.
            }
        }
    }
}
