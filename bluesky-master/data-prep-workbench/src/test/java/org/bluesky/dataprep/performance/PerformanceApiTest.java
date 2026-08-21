package org.bluesky.dataprep.performance;

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

    @Autowired
    private MockMvc mockMvc;

    @Test
    void seededOpenApRowsListed() throws Exception {
        mockMvc.perform(get("/api/performance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(org.hamcrest.Matchers.greaterThanOrEqualTo(3)))
                .andExpect(jsonPath("$.items[?(@.code=='A320')].performanceSource").value("OPENAP"))
                .andExpect(jsonPath("$.items[?(@.code=='A320')].maximumMach").value(0.82))
                .andExpect(jsonPath("$.items[?(@.code=='C919')].performanceSource").value("MANUAL"));
    }

    @Test
    void manualCreateAndUpdateAllowed() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/performance")
                        .contentType(APPLICATION_JSON)
                        .content("{\"code\":\"ARJ21\",\"name\":\"ARJ21\",\"manufacturer\":\"COMAC\","
                                + "\"performanceSource\":\"MANUAL\",\"wakeTurbulenceCategory\":\"M\","
                                + "\"maximumAltitudeFt\":35000,\"maximumMach\":0.78}"))
                .andExpect(status().isOk())
                .andReturn();
        String id = com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.id");

        mockMvc.perform(put("/api/performance/{id}", id)
                        .contentType(APPLICATION_JSON)
                        .content("{\"code\":\"ARJ21\",\"name\":\"ARJ21-700\",\"manufacturer\":\"COMAC\","
                                + "\"performanceSource\":\"MANUAL\",\"maximumAltitudeFt\":36000,"
                                + "\"maximumMach\":0.78,\"revision\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modelName").doesNotExist())
                .andExpect(jsonPath("$.name").value("ARJ21-700"));
    }

    @Test
    void openApRowReadOnly() throws Exception {
        MvcResult list = mockMvc.perform(get("/api/performance")).andReturn();
        String a320Id = null;
        for (Object item : com.jayway.jsonpath.JsonPath.<java.util.List<Object>>read(
                list.getResponse().getContentAsString(), "$.items")) {
            if ("A320".equals(((java.util.Map<String, Object>) item).get("code"))) {
                a320Id = (String) ((java.util.Map<String, Object>) item).get("id");
            }
        }
        mockMvc.perform(put("/api/performance/{id}", a320Id)
                        .contentType(APPLICATION_JSON)
                        .content("{\"code\":\"A320\",\"name\":\"尝试改\",\"performanceSource\":\"OPENAP\",\"revision\":0}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void invalidSourceRejected() throws Exception {
        mockMvc.perform(post("/api/performance")
                        .contentType(APPLICATION_JSON)
                        .content("{\"code\":\"X99\",\"name\":\"坏来源\",\"performanceSource\":\"WIKI\"}"))
                .andExpect(status().isBadRequest());
    }
}
