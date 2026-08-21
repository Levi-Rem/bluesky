package org.bluesky.dataprep.nav;

import org.bluesky.dataprep.DataPrepApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = DataPrepApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class NavPointApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void listReturnsSeededPrototypeRowsPaged() throws Exception {
        mockMvc.perform(get("/api/nav-point").param("page", "0").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(org.hamcrest.Matchers.greaterThanOrEqualTo(3)))
                .andExpect(jsonPath("$.items[0].code").value("AND"))
                .andExpect(jsonPath("$.items[?(@.code=='PUD')].name").value("浦东导航台"));
    }

    @Test
    void createUpdateAndStatusFlowWithRevisionBump() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/nav-point")
                        .contentType(APPLICATION_JSON)
                        .content("{\"code\":\"TEST1\",\"name\":\"测试点\",\"pointType\":\"VOR\","
                                + "\"longitude\":121.5,\"latitude\":31.2,\"frequencyMhz\":117.5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(0))
                .andExpect(jsonPath("$.sourceType").value("MANUAL"))
                .andReturn();
        String id = com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.id");

        mockMvc.perform(put("/api/nav-point/{id}", id)
                        .contentType(APPLICATION_JSON)
                        .content("{\"code\":\"TEST1\",\"name\":\"测试点改\",\"pointType\":\"VOR\","
                                + "\"longitude\":121.6,\"latitude\":31.3,\"frequencyMhz\":117.5,\"revision\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("测试点改"))
                .andExpect(jsonPath("$.revision").value(1));

        mockMvc.perform(post("/api/nav-point/{id}/status", id)
                        .param("status", "DISABLED").param("revision", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISABLED"));
    }

    @Test
    void duplicateCodeRejectedAsConflict() throws Exception {
        mockMvc.perform(post("/api/nav-point")
                        .contentType(APPLICATION_JSON)
                        .content("{\"code\":\"PUD\",\"name\":\"重复编码\",\"pointType\":\"FIX\","
                                + "\"longitude\":121.0,\"latitude\":31.0}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409));
    }

    @Test
    void staleRevisionRejectedAsConflict() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/nav-point")
                        .contentType(APPLICATION_JSON)
                        .content("{\"code\":\"TEST2\",\"name\":\"乐观锁\",\"pointType\":\"FIX\","
                                + "\"longitude\":121.0,\"latitude\":31.0}"))
                .andReturn();
        String id = com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.id");

        mockMvc.perform(put("/api/nav-point/{id}", id)
                        .contentType(APPLICATION_JSON)
                        .content("{\"code\":\"TEST2\",\"name\":\"旧版本\",\"pointType\":\"FIX\","
                                + "\"longitude\":121.0,\"latitude\":31.0,\"revision\":99}"))
                .andExpect(status().isConflict());
    }

    @Test
    void blueskySourceIsReadOnly() throws Exception {
        MvcResult found = mockMvc.perform(get("/api/nav-point")).andReturn();
        String pudId = null;
        for (Object item : com.jayway.jsonpath.JsonPath.<java.util.List<Object>>read(
                found.getResponse().getContentAsString(), "$.items")) {
            if ("PUD".equals(((java.util.Map<String, Object>) item).get("code"))) {
                pudId = (String) ((java.util.Map<String, Object>) item).get("id");
            }
        }
        assertThat(pudId).isNotNull();

        mockMvc.perform(put("/api/nav-point/{id}", pudId)
                        .contentType(APPLICATION_JSON)
                        .content("{\"code\":\"PUD\",\"name\":\"尝试改只读\",\"pointType\":\"VOR\","
                                + "\"longitude\":121.8,\"latitude\":31.1,\"revision\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("只读")));

        mockMvc.perform(delete("/api/nav-point/{id}", pudId).param("revision", "0"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void logicalDeleteHidesRowFromList() throws Exception {
        long totalBefore = com.jayway.jsonpath.JsonPath.<Integer>read(
                mockMvc.perform(get("/api/nav-point")).andReturn().getResponse().getContentAsString(), "$.total");

        MvcResult created = mockMvc.perform(post("/api/nav-point")
                        .contentType(APPLICATION_JSON)
                        .content("{\"code\":\"TEST3\",\"name\":\"待删\",\"pointType\":\"FIX\","
                                + "\"longitude\":120.0,\"latitude\":30.0}"))
                .andReturn();
        String id = com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.id");

        mockMvc.perform(delete("/api/nav-point/{id}", id).param("revision", "0"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/nav-point/{id}", id))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/nav-point"))
                .andExpect(jsonPath("$.total").value((int) totalBefore));
    }

    @Test
    void invalidLongitudeAndTypeRejected() throws Exception {
        mockMvc.perform(post("/api/nav-point")
                        .contentType(APPLICATION_JSON)
                        .content("{\"code\":\"TEST4\",\"name\":\"坏经度\",\"pointType\":\"FIX\","
                                + "\"longitude\":200.0,\"latitude\":31.0}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/nav-point")
                        .contentType(APPLICATION_JSON)
                        .content("{\"code\":\"TEST5\",\"name\":\"坏类型\",\"pointType\":\"XYZ\","
                                + "\"longitude\":121.0,\"latitude\":31.0}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/nav-point")
                        .contentType(APPLICATION_JSON)
                        .content("{\"name\":\"缺编码\",\"pointType\":\"FIX\","
                                + "\"longitude\":121.0,\"latitude\":31.0}"))
                .andExpect(status().isBadRequest());
    }
}
