package org.bluesky.dataprep.airport;

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
class AirportApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void seededAirportsListed() throws Exception {
        mockMvc.perform(get("/api/airport"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(org.hamcrest.Matchers.greaterThanOrEqualTo(2)))
                .andExpect(jsonPath("$.items[?(@.code=='ZSPD')].name").value("上海浦东"))
                .andExpect(jsonPath("$.items[?(@.code=='ZSPD')].icao").value("ZSPD"));
    }

    @Test
    void detailIncludesRunwaysAndReplaceAllOnUpdate() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/airport")
                        .contentType(APPLICATION_JSON)
                        .content("{\"code\":\"ZBTJ\",\"name\":\"天津滨海\",\"icao\":\"ZBTJ\","
                                + "\"longitude\":117.35,\"latitude\":39.12,\"elevationM\":3,"
                                + "\"runways\":[{\"designation\":\"16R/34L\",\"lengthM\":3600,\"widthM\":60,"
                                + "\"trueHeadingDeg\":162.0,\"surface\":\"ASPHALT\"}]}"))
                .andExpect(status().isOk())
                .andReturn();
        String id = com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.id");

        mockMvc.perform(get("/api/airport/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runways.length()").value(1))
                .andExpect(jsonPath("$.runways[0].designation").value("16R/34L"))
                .andExpect(jsonPath("$.runways[0].orderNo").value(0));

        mockMvc.perform(put("/api/airport/{id}", id)
                        .contentType(APPLICATION_JSON)
                        .content("{\"code\":\"ZBTJ\",\"name\":\"天津滨海\",\"icao\":\"ZBTJ\","
                                + "\"longitude\":117.35,\"latitude\":39.12,\"revision\":0,"
                                + "\"runways\":[{\"designation\":\"16R/34L\",\"lengthM\":3600},"
                                + "{\"designation\":\"17L/35R\",\"lengthM\":3200}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(1))
                .andExpect(jsonPath("$.runways.length()").value(2))
                .andExpect(jsonPath("$.runways[1].designation").value("17L/35R"));

        mockMvc.perform(put("/api/airport/{id}", id)
                        .contentType(APPLICATION_JSON)
                        .content("{\"code\":\"ZBTJ\",\"name\":\"天津滨海更名\",\"icao\":\"ZBTJ\","
                                + "\"longitude\":117.35,\"latitude\":39.12,\"revision\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runways.length()").value(2));
    }

    @Test
    void runwayDesignationRequired() throws Exception {
        mockMvc.perform(post("/api/airport")
                        .contentType(APPLICATION_JSON)
                        .content("{\"code\":\"ZBAD\",\"name\":\"大兴测试\",\"longitude\":116.4,\"latitude\":39.5,"
                                + "\"runways\":[{\"lengthM\":3800}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("跑道号")));
    }

    @Test
    void blueskyAirportReadOnly() throws Exception {
        MvcResult list = mockMvc.perform(get("/api/airport")).andReturn();
        String zspdId = null;
        for (Object item : com.jayway.jsonpath.JsonPath.<java.util.List<Object>>read(
                list.getResponse().getContentAsString(), "$.items")) {
            if ("ZSPD".equals(((java.util.Map<String, Object>) item).get("code"))) {
                zspdId = (String) ((java.util.Map<String, Object>) item).get("id");
            }
        }
        mockMvc.perform(put("/api/airport/{id}", zspdId)
                        .contentType(APPLICATION_JSON)
                        .content("{\"code\":\"ZSPD\",\"name\":\"尝试改\",\"longitude\":121.8,\"latitude\":31.1,\"revision\":0}"))
                .andExpect(status().isBadRequest());
    }
}
