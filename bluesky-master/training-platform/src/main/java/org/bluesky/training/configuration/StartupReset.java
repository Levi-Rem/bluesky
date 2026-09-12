package org.bluesky.training.configuration;

import org.bluesky.training.adapter.SimulationGateway;
import org.bluesky.training.adapter.EngineHealth;
import org.bluesky.training.adapter.AdapterUnavailableException;
import org.bluesky.training.persistence.BootstrapMapper;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 启动清空仅限显式声明的演示/测试环境（详细设计 3.3.7：生产不执行启动清空；
 * 评审 P0-2）。默认不装配；demo/test 配置显式设置
 * training.startup-reset.enabled=true 时生效。
 */
@Component
@ConditionalOnProperty(name = "training.startup-reset.enabled", havingValue = "true")
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
