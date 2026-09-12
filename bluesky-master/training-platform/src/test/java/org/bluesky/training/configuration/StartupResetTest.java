package org.bluesky.training.configuration;

import org.bluesky.training.adapter.EngineHealth;
import org.bluesky.training.adapter.AdapterUnavailableException;
import org.bluesky.training.adapter.SimulationGateway;
import org.bluesky.training.persistence.BootstrapMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0-2：启动清空只允许显式声明的环境执行（详细设计 3.3.7）。
 * 默认装配必须不注册该组件，生产主配置不含 training.startup-reset.enabled。
 */
class StartupResetTest {

    /** 装配门禁：默认/关闭时不注册，显式开启才注册（ApplicationContextRunner 不会触发 run）。 */
    private final ApplicationContextRunner gating = new ApplicationContextRunner()
            .withUserConfiguration(GatedStartupReset.class)
            .withBean(BootstrapMapper.class, () -> mock(BootstrapMapper.class))
            .withBean(SimulationGateway.class, () -> mock(SimulationGateway.class));

    @Test
    void notRegisteredWithoutExplicitEnablement() {
        gating.run(context -> assertTrue(
                context.getBeanNamesForType(StartupReset.class).length == 0,
                "未显式开启 training.startup-reset.enabled 时不得装配启动清空（评审 P0-2）"));
        gating.withPropertyValues("training.startup-reset.enabled=false")
                .run(context -> assertTrue(
                        context.getBeanNamesForType(StartupReset.class).length == 0,
                        "显式关闭时不得装配启动清空"));
    }

    @Test
    void registeredOnlyWhenExplicitlyEnabled() {
        gating.withPropertyValues("training.startup-reset.enabled=true")
                .run(context -> assertTrue(
                        context.getBeanNamesForType(StartupReset.class).length == 1,
                        "显式开启时装配启动清空（demo/test 环境）"));
    }

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

    /** 与 StartupReset 相同注解的镜像配置：让 ApplicationContextRunner 验证门控语义本身。 */
    @org.springframework.context.annotation.Configuration
    static class GatedStartupReset {
        @Bean
        @ConditionalOnProperty(name = "training.startup-reset.enabled", havingValue = "true")
        StartupReset startupReset(BootstrapMapper mapper, SimulationGateway gateway) {
            return new StartupReset(mapper, gateway);
        }
    }
}
