package org.bluesky.dataprep.airspace;

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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = DataPrepApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AirspaceApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void seededAirspacesWithAltitudeRange() throws Exception {
        mockMvc.perform(get("/api/airspace"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.items[?(@.code=='TMA-01')].airspaceType").value("TMA"))
                .andExpect(jsonPath("$.items[?(@.code=='TMA-01')].upperValue").value(19700.0))
                .andExpect(jsonPath("$.items[?(@.code=='R-210')].status").value("DISABLED"))
                .andExpect(jsonPath("$.items[?(@.code=='ZSHA')].sourceType").value("BLUESKY"));
    }

    @Test
    void createWithGeoJsonBoundary() throws Exception {
        mockMvc.perform(post("/api/airspace")
                        .contentType(APPLICATION_JSON)
                        .content("{\"code\":\"T-901\",\"name\":\"测试禁区\",\"airspaceType\":\"PROHIBITED\","
                                + "\"boundary\":\"{\\\"type\\\":\\\"Polygon\\\",\\\"coordinates\\\":[[[120.0,30.0],[121.0,30.0],"
                                + "[121.0,31.0],[120.0,31.0],[120.0,30.0]]]}\","
                                + "\"lowerValue\":0,\"lowerReference\":\"SFC\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.boundary").value(
                        org.hamcrest.Matchers.containsString("\"type\":\"Polygon\"")));
    }

    @Test
    void invalidTypeRejected() throws Exception {
        mockMvc.perform(post("/api/airspace")
                        .contentType(APPLICATION_JSON)
                        .content("{\"code\":\"T-902\",\"name\":\"坏类型\",\"airspaceType\":\"SPA\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void invalidGeoJsonRejected() throws Exception {
        mockMvc.perform(post("/api/airspace")
                        .contentType(APPLICATION_JSON)
                        .content("{\"code\":\"T-INVALID-GEO\",\"name\":\"坏边界\",\"airspaceType\":\"TMA\","
                                + "\"boundary\":\"{\\\"a\\\":1}\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Polygon")));
    }

    @Test
    void manualStatusToggleAllowed() throws Exception {
        MvcResult list = mockMvc.perform(get("/api/airspace")).andReturn();
        String r210Id = null;
        for (Object item : com.jayway.jsonpath.JsonPath.<java.util.List<Object>>read(
                list.getResponse().getContentAsString(), "$.items")) {
            if ("R-210".equals(((java.util.Map<String, Object>) item).get("code"))) {
                r210Id = (String) ((java.util.Map<String, Object>) item).get("id");
            }
        }
        mockMvc.perform(post("/api/airspace/{id}/status", r210Id)
                        .param("status", "ENABLED").param("revision", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ENABLED"));
    }
}
