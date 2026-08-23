package org.bluesky.training.display;

import org.bluesky.training.TrainingPlatformApplication;
import org.bluesky.training.adapter.SimulationGateway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = TrainingPlatformApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class DisplaySettingsApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @MockBean
    private SimulationGateway simulationGateway;

    @AfterEach
    void restoreDisplaySettings() {
        restore("ui.trackColor", "#3fae6d");
        restore("ui.selectedTrackColor", "#27e58d");
        restore("ui.mapWaypointColor", "#7FD3FF");
        restore("ui.mapAirwayColor", "#4AA8D8");
        restore("ui.mapSectorColor", "#D6A7FF");
        restore("ui.mapSectorFillColor", "#7B4DB3");
        restore("ui.mapWeatherColor", "#FFCF66");
        restore("ui.mapWeatherFillColor", "#D9822B");
    }

    @Test
    void savesAllColorsAndNormalizesHexCase() throws Exception {
        mockMvc.perform(put("/api/v1/workstation/display-settings")
                        .contentType(APPLICATION_JSON)
                        .content(validBody("#112233")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trackColor").value("#112233"))
                .andExpect(jsonPath("$.mapWeatherFillColor").value("#D9822B"));

        String stored = jdbc.queryForObject(
                "SELECT parameter_value FROM system_parameter WHERE parameter_key='ui.trackColor'", String.class);
        org.assertj.core.api.Assertions.assertThat(stored).isEqualTo("#112233");
    }

    @Test
    void rejectsInvalidColorWithoutPartiallyUpdatingSettings() throws Exception {
        String before = jdbc.queryForObject(
                "SELECT parameter_value FROM system_parameter WHERE parameter_key='ui.trackColor'", String.class);
        String body = validBody("red");

        mockMvc.perform(put("/api/v1/workstation/display-settings")
                        .contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("trackColor"));

        String after = jdbc.queryForObject(
                "SELECT parameter_value FROM system_parameter WHERE parameter_key='ui.trackColor'", String.class);
        org.assertj.core.api.Assertions.assertThat(after).isEqualTo(before);
    }

    private String validBody(String trackColor) {
        return "{"
                + "\"trackColor\":\"" + trackColor + "\","
                + "\"selectedTrackColor\":\"#27e58d\","
                + "\"mapWaypointColor\":\"#7fd3ff\","
                + "\"mapAirwayColor\":\"#4aa8d8\","
                + "\"mapSectorColor\":\"#d6a7ff\","
                + "\"mapSectorFillColor\":\"#7b4db3\","
                + "\"mapWeatherColor\":\"#ffcf66\","
                + "\"mapWeatherFillColor\":\"#d9822b\"}"
                ;
    }

    private void restore(String key, String value) {
        jdbc.update("UPDATE system_parameter SET parameter_value=? WHERE parameter_key=?", value, key);
    }
}
