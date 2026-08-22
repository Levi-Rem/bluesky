package org.bluesky.dataprep.performance;

import com.jayway.jsonpath.JsonPath;
import org.bluesky.dataprep.DataPrepApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = DataPrepApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PerformanceApiTest {
    @Autowired private MockMvc mockMvc;

    @Test
    void listUsesLayerStructureWithoutSourceFields() throws Exception {
        mockMvc.perform(get("/api/performance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(org.hamcrest.Matchers.greaterThanOrEqualTo(3)))
                .andExpect(jsonPath("$.items[?(@.code=='A320')].altitudeLayer").value("F398"))
                .andExpect(jsonPath("$.items[0].performanceSource").doesNotExist())
                .andExpect(jsonPath("$.items[0].sourceType").doesNotExist());
    }

    @Test
    void createLayerAndSynchronizeCommonPerformance() throws Exception {
        MvcResult first = mockMvc.perform(post("/api/performance")
                        .contentType(APPLICATION_JSON)
                        .content("{\"code\":\"ARJ21\",\"name\":\"ARJ21\",\"manufacturer\":\"COMAC\","+
                                "\"icaoWakeCategory\":\"M\",\"reacatWakeCategory\":\"M\","+
                                "\"altitudeLayer\":\"F100\",\"cruiseSpeed\":\"N0300\","+
                                "\"holdingSpeedLow\":\"N0180\",\"turnResponse1\":50}"))
                .andExpect(status().isOk())
                .andReturn();
        String firstId = JsonPath.read(first.getResponse().getContentAsString(), "$.id");

        mockMvc.perform(post("/api/performance")
                        .contentType(APPLICATION_JSON)
                        .content("{\"code\":\"ARJ21\",\"name\":\"ARJ21\",\"manufacturer\":\"COMAC\","+
                                "\"icaoWakeCategory\":\"M\",\"reacatWakeCategory\":\"M\","+
                                "\"altitudeLayer\":\"F200\",\"cruiseSpeed\":\"N0350\","+
                                "\"holdingSpeedLow\":\"N0190\",\"turnResponse1\":66}"))
                .andExpect(status().isOk());

        MvcResult refreshed = mockMvc.perform(get("/api/performance/{id}", firstId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.holdingSpeedLow").value("N0190"))
                .andExpect(jsonPath("$.turnResponse1").value(66))
                .andExpect(jsonPath("$.cruiseSpeed").value("N0300"))
                .andReturn();
        int revision = JsonPath.read(refreshed.getResponse().getContentAsString(), "$.revision");

        mockMvc.perform(put("/api/performance/{id}", firstId)
                        .contentType(APPLICATION_JSON)
                        .content("{\"code\":\"ARJ21\",\"name\":\"ARJ21-700\",\"manufacturer\":\"COMAC\","+
                                "\"icaoWakeCategory\":\"M\",\"reacatWakeCategory\":\"M\","+
                                "\"altitudeLayer\":\"F100\",\"cruiseSpeed\":\"N0310\","+
                                "\"holdingSpeedLow\":\"N0200\",\"turnResponse1\":75,\"revision\":"+revision+"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("ARJ21-700"))
                .andExpect(jsonPath("$.cruiseSpeed").value("N0310"));
    }

    @Test
    void duplicateAltitudeLayerRejected() throws Exception {
        String body = "{\"code\":\"TESTL\",\"name\":\"TESTL\",\"icaoWakeCategory\":\"L\","+
                "\"reacatWakeCategory\":\"L\",\"altitudeLayer\":\"F050\"}";
        mockMvc.perform(post("/api/performance").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/performance").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }
}
