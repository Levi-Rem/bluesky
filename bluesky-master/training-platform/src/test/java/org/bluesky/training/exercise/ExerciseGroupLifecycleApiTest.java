package org.bluesky.training.exercise;

import org.bluesky.training.TrainingPlatformApplication;
import org.bluesky.training.adapter.EngineHealth;
import org.bluesky.training.adapter.SimulationGateway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = TrainingPlatformApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class ExerciseGroupLifecycleApiTest {
    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private SimulationGateway simulationGateway;

    @Test
    void startsReadyExerciseGroup() throws Exception {
        when(simulationGateway.health())
                .thenReturn(new EngineHealth(true, "CONNECTED", "OPENAP", "BlueSky 已连接"));

        mockMvc.perform(post("/api/v1/exercise-groups/GROUP-DEFAULT/start"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("GROUP-DEFAULT"))
                .andExpect(jsonPath("$.state").value("RUNNING"));

        verify(simulationGateway).start();
    }

    @Test
    void pausesRunningExerciseGroup() throws Exception {
        mockMvc.perform(post("/api/v1/exercise-groups/GROUP-DEFAULT/start"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/exercise-groups/GROUP-DEFAULT/pause"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("PAUSED"));

        verify(simulationGateway).pause();
    }

    @Test
    void resumesPausedExerciseGroupAndKeepsRepeatedResumeIdempotent() throws Exception {
        mockMvc.perform(post("/api/v1/exercise-groups/GROUP-DEFAULT/start"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/exercise-groups/GROUP-DEFAULT/pause"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/exercise-groups/GROUP-DEFAULT/resume"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("RUNNING"));
        mockMvc.perform(post("/api/v1/exercise-groups/GROUP-DEFAULT/resume"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("RUNNING"));

        verify(simulationGateway, times(1)).resume();
    }
}
