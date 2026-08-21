package org.bluesky.dataprep.weather;

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
class WeatherApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void mixedWeatherListCoversThreeCategories() throws Exception {
        mockMvc.perform(get("/api/weather"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(org.hamcrest.Matchers.greaterThanOrEqualTo(3)))
                .andExpect(jsonPath("$.items[?(@.code=='WIND-E01')].dataType").value("三维风场"))
                .andExpect(jsonPath("$.items[?(@.code=='MET-ZSPD')].dataType").value("机场气象"))
                .andExpect(jsonPath("$.items[?(@.code=='CB-07')].dataType").value("重要天气区域"))
                .andExpect(jsonPath("$.items[?(@.code=='MET-ZSPD')].relatedArea").value("ZSPD"));
    }

    @Test
    void windFieldCrudWithPoints() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/wind-field")
                        .contentType(APPLICATION_JSON)
                        .content("{\"code\":\"WIND-T1\",\"name\":\"测试风场\",\"windFieldType\":\"THREE_DIMENSIONAL\","
                                + "\"points\":[{\"longitude\":121.5,\"latitude\":31.2,\"altitudeM\":2000,"
                                + "\"windDirectionDeg\":90.0,\"windSpeedMs\":5.5}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.points.length()").value(1))
                .andExpect(jsonPath("$.points[0].altitudeM").value(2000))
                .andReturn();
        String id = com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.id");

        mockMvc.perform(get("/api/wind-field/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.points[0].windSpeedMs").value(5.5));
    }

    @Test
    void globalConstantWindRequiresDirectionAndSpeed() throws Exception {
        mockMvc.perform(post("/api/wind-field")
                        .contentType(APPLICATION_JSON)
                        .content("{\"code\":\"WIND-T2\",\"name\":\"缺风速\",\"windFieldType\":\"GLOBAL_CONSTANT\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("恒定风")));
    }

    @Test
    void windPointMissingFieldRejected() throws Exception {
        mockMvc.perform(post("/api/wind-field")
                        .contentType(APPLICATION_JSON)
                        .content("{\"code\":\"WIND-T3\",\"name\":\"缺高度\",\"windFieldType\":\"THREE_DIMENSIONAL\","
                                + "\"points\":[{\"longitude\":121.5,\"latitude\":31.2,"
                                + "\"windDirectionDeg\":90.0,\"windSpeedMs\":5.5}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("高度")));
    }
}
