package org.bluesky.dataprep.map;

import org.bluesky.dataprep.DataPrepApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = DataPrepApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MapApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void fiveLayersWithSeededFeatures() throws Exception {
        mockMvc.perform(get("/api/map/layers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.layers.length()").value(5))
                .andExpect(jsonPath("$.layers[0].category").value("NAVIGATION"))
                .andExpect(jsonPath("$.layers[0].count").value(org.hamcrest.Matchers.greaterThanOrEqualTo(3)))
                .andExpect(jsonPath("$.layers[1].category").value("AIRSPACE"))
                .andExpect(jsonPath("$.layers[1].count").value(3))
                .andExpect(jsonPath("$.layers[2].category").value("AIRWAY"))
                .andExpect(jsonPath("$.layers[2].count").value(3))
                .andExpect(jsonPath("$.layers[3].category").value("WEATHER"))
                .andExpect(jsonPath("$.layers[3].count").value(org.hamcrest.Matchers.greaterThanOrEqualTo(4)))
                .andExpect(jsonPath("$.layers[4].category").value("RADAR"))
                .andExpect(jsonPath("$.layers[4].count").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));
    }

    @Test
    void airwayGeometryIsLineStringFromSegments() throws Exception {
        mockMvc.perform(get("/api/map/layers"))
                .andExpect(jsonPath("$.layers[2].features[?(@.code=='A593')].geometry.type").value("LineString"))
                .andExpect(jsonPath(
                        "$.layers[2].features[?(@.code=='A593')].geometry.coordinates[0][0]").exists());
    }

    @Test
    void weatherAndRadarCoordinatesAreNumeric() throws Exception {
        mockMvc.perform(get("/api/map/layers"))
                .andExpect(jsonPath(
                        "$.layers[?(@.category=='WEATHER')].features[0].geometry.coordinates[0]").exists())
                .andExpect(jsonPath(
                        "$.layers[?(@.category=='RADAR')].features[0].geometry.coordinates[0][0]").exists());
    }

    @Test
    void radarCoverageIsApproximatePolygon() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/map/layers")).andReturn();
        List<Object> radarFeatures = com.jayway.jsonpath.JsonPath.<java.util.List<Object>>read(
                result.getResponse().getContentAsString(),
                "$.layers[?(@.category=='RADAR')].features[0]");
        org.assertj.core.api.Assertions.assertThat(radarFeatures).isNotEmpty();
    }

    @Test
    void batchUpdateMovesNavPointAndRenamesAirspace() throws Exception {
        MvcResult navList = mockMvc.perform(get("/api/nav-point").param("size", "100")).andReturn();
        String andId = null;
        int andRevision = 0;
        for (Object item : com.jayway.jsonpath.JsonPath.<java.util.List<Object>>read(
                navList.getResponse().getContentAsString(), "$.items")) {
            if ("AND".equals(((java.util.Map<String, Object>) item).get("code"))) {
                andId = (String) ((java.util.Map<String, Object>) item).get("id");
                andRevision = ((Number) ((java.util.Map<String, Object>) item).get("revision")).intValue();
            }
        }

        MvcResult asList = mockMvc.perform(get("/api/airspace").param("size", "100")).andReturn();
        String tmaId = null;
        int tmaRevision = 0;
        for (Object item : com.jayway.jsonpath.JsonPath.<java.util.List<Object>>read(
                asList.getResponse().getContentAsString(), "$.items")) {
            if ("TMA-01".equals(((java.util.Map<String, Object>) item).get("code"))) {
                tmaId = (String) ((java.util.Map<String, Object>) item).get("id");
                tmaRevision = ((Number) ((java.util.Map<String, Object>) item).get("revision")).intValue();
            }
        }

        mockMvc.perform(put("/api/map/features")
                        .contentType(APPLICATION_JSON)
                        .content("{\"operations\":["
                                + "{\"operationType\":\"UPDATE_GEOMETRY\",\"entityType\":\"nav-point\","
                                + "\"entityId\":\"" + andId + "\",\"revision\":" + andRevision + ","
                                + "\"geometry\":\"{\\\"type\\\":\\\"Point\\\",\\\"coordinates\\\":[121.5,31.4]}\"},"
                                + "{\"operationType\":\"UPDATE_PROPERTIES\",\"entityType\":\"airspace\","
                                + "\"entityId\":\"" + tmaId + "\",\"revision\":" + tmaRevision + ","
                                + "\"properties\":{\"name\":\"终端管制区一号改\"}}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.saved").value(2));

        mockMvc.perform(get("/api/nav-point/{id}", andId))
                .andExpect(jsonPath("$.longitude").value(121.5))
                .andExpect(jsonPath("$.latitude").value(31.4));

        mockMvc.perform(get("/api/airspace/{id}", tmaId))
                .andExpect(jsonPath("$.name").value("终端管制区一号改"));
    }

    @Test
    void batchRollsBackOnBadOperation() throws Exception {
        MvcResult navList = mockMvc.perform(get("/api/nav-point").param("size", "100")).andReturn();
        String sasanId = null;
        int sasanRevision = 0;
        for (Object item : com.jayway.jsonpath.JsonPath.<java.util.List<Object>>read(
                navList.getResponse().getContentAsString(), "$.items")) {
            if ("SASAN".equals(((java.util.Map<String, Object>) item).get("code"))) {
                sasanId = (String) ((java.util.Map<String, Object>) item).get("id");
                sasanRevision = ((Number) ((java.util.Map<String, Object>) item).get("revision")).intValue();
            }
        }

        mockMvc.perform(put("/api/map/features")
                        .contentType(APPLICATION_JSON)
                        .content("{\"operations\":["
                                + "{\"operationType\":\"UPDATE_PROPERTIES\",\"entityType\":\"nav-point\","
                                + "\"entityId\":\"" + sasanId + "\",\"revision\":" + sasanRevision + ","
                                + "\"properties\":{\"name\":\"会被回滚的名字\"}},"
                                + "{\"operationType\":\"UPDATE_GEOMETRY\",\"entityType\":\"wind-field\","
                                + "\"entityId\":\"whatever\",\"geometry\":\"{}\"}]}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/nav-point/{id}", sasanId))
                .andExpect(jsonPath("$.name").value("莎山点"));
    }

    @Test
    void emptyOperationsRejected() throws Exception {
        mockMvc.perform(put("/api/map/features")
                        .contentType(APPLICATION_JSON)
                        .content("{\"operations\":[]}"))
                .andExpect(status().isBadRequest());
    }
}
