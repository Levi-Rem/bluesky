package org.bluesky.dataprep.airway;

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
class AirwayApiTest {

    @Autowired
    private MockMvc mockMvc;

    private String navPointId(String code) throws Exception {
        MvcResult list = mockMvc.perform(get("/api/nav-point").param("size", "100")).andReturn();
        for (Object item : com.jayway.jsonpath.JsonPath.<java.util.List<Object>>read(
                list.getResponse().getContentAsString(), "$.items")) {
            if (code.equals(((java.util.Map<String, Object>) item).get("code"))) {
                return (String) ((java.util.Map<String, Object>) item).get("id");
            }
        }
        throw new AssertionError("种子导航点缺失：" + code);
    }

    @Test
    void seededAirwayResolvesSegmentPointCodes() throws Exception {
        mockMvc.perform(get("/api/airway"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.items[?(@.code=='A593')].airwayDirection").value("TWO_WAY"))
                .andExpect(jsonPath("$.items[?(@.code=='A593')].segments[0].startPointCode").value("PUD"))
                .andExpect(jsonPath("$.items[?(@.code=='A593')].segments[0].endPointCode").value("SASAN"));
    }

    @Test
    void createWithSegmentsAndReplaceOnUpdate() throws Exception {
        String pud = navPointId("PUD");
        String and = navPointId("AND");
        String sasan = navPointId("SASAN");

        MvcResult created = mockMvc.perform(post("/api/airway")
                        .contentType(APPLICATION_JSON)
                        .content("{\"code\":\"T-900\",\"name\":\"测试航路\",\"airwayDirection\":\"TWO_WAY\","
                                + "\"lowerValue\":6000,\"lowerReference\":\"FL\","
                                + "\"segments\":[{\"startPointId\":\"" + pud + "\",\"endPointId\":\"" + sasan + "\"},"
                                + "{\"startPointId\":\"" + sasan + "\",\"endPointId\":\"" + and + "\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.segments.length()").value(2))
                .andExpect(jsonPath("$.segments[0].startPointCode").value("PUD"))
                .andExpect(jsonPath("$.segments[1].endPointCode").value("AND"))
                .andReturn();
        String id = com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.id");

        mockMvc.perform(post("/api/airway")
                        .contentType(APPLICATION_JSON)
                        .content("{\"code\":\"T-901\",\"name\":\"单段航路\",\"airwayDirection\":\"ONE_WAY\","
                                + "\"segments\":[{\"startPointId\":\"" + and + "\",\"endPointId\":\"" + pud + "\"}]}"))
                .andExpect(status().isOk());
    }

    @Test
    void segmentReferencingMissingPointRejected() throws Exception {
        mockMvc.perform(post("/api/airway")
                        .contentType(APPLICATION_JSON)
                        .content("{\"code\":\"T-902\",\"name\":\"坏引用\",\"airwayDirection\":\"ONE_WAY\","
                                + "\"segments\":[{\"startPointId\":\"not-exist\",\"endPointId\":\"also-missing\"}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("导航点不存在")));
    }

    @Test
    void invalidDirectionRejected() throws Exception {
        mockMvc.perform(post("/api/airway")
                        .contentType(APPLICATION_JSON)
                        .content("{\"code\":\"T-903\",\"name\":\"坏方向\",\"airwayDirection\":\"REVERSE\"}"))
                .andExpect(status().isBadRequest());
    }
}
