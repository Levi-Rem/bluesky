package org.bluesky.training.event;

import org.bluesky.training.TrainingPlatformApplication;
import org.bluesky.training.adapter.EngineHealth;
import org.bluesky.training.adapter.SimulationGateway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = TrainingPlatformApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EventStreamApiTest {
    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private SimulationGateway simulationGateway;

    @Autowired
    private EventStreamService eventStreamService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void sendsCompleteSnapshotAsFirstSseEvent() throws Exception {
        when(simulationGateway.health())
                .thenReturn(new EngineHealth(true, "CONNECTED", "OPENAP", "BlueSky 已连接"));

        MvcResult result = mockMvc.perform(get("/api/v1/events")
                        .param("exerciseGroupId", "GROUP-DEFAULT")
                        .header("Accept", "text/event-stream"))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andReturn();

        String streamPrefix = result.getResponse().getContentAsString();
        assertThat(streamPrefix)
                .contains("event:snapshot")
                .contains("GROUP-DEFAULT")
                .contains("PP-DEFAULT");
    }

    @Test
    void publishesExerciseStateAfterStart() throws Exception {
        when(simulationGateway.health())
                .thenReturn(new EngineHealth(true, "CONNECTED", "OPENAP", "BlueSky 已连接"));
        MvcResult stream = mockMvc.perform(get("/api/v1/events")
                        .param("exerciseGroupId", "GROUP-DEFAULT")
                        .header("Accept", "text/event-stream"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(post("/api/v1/exercise-groups/GROUP-DEFAULT/start"))
                .andExpect(status().isOk());

        assertThat(stream.getResponse().getContentAsString())
                .contains("event:exercise-state")
                .contains("RUNNING");
    }

    @Test
    void publishesTransactionalEventsOnlyAfterCommit() throws Exception {
        MvcResult stream = mockMvc.perform(get("/api/v1/events")
                        .param("exerciseGroupId", "GROUP-DEFAULT")
                        .header("Accept", "text/event-stream"))
                .andExpect(request().asyncStarted())
                .andReturn();
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        transaction.execute(status -> {
            eventStreamService.publishAfterCommit("test-event", "rolled-back");
            status.setRollbackOnly();
            return null;
        });
        assertThat(stream.getResponse().getContentAsString()).doesNotContain("rolled-back");

        transaction.execute(status -> {
            eventStreamService.publishAfterCommit("test-event", "committed");
            return null;
        });
        assertThat(stream.getResponse().getContentAsString())
                .contains("event:test-event")
                .contains("committed");
    }
}
