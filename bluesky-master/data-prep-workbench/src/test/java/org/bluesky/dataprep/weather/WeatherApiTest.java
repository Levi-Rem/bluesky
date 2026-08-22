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
    void weatherListUsesRegionalWeatherStructure() throws Exception {
        mockMvc.perform(get("/api/weather"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.code=='CB-07')].name").value("雷暴区 07"))
                .andExpect(jsonPath("$.items[?(@.code=='CB-07')].weatherType").value("THUNDERSTORM"))
                .andExpect(jsonPath("$.items[?(@.code=='CB-07')].lowerLimit").value("S0060"))
                .andExpect(jsonPath("$.items[?(@.code=='CB-07')].upperLimit").value("S1100"));
    }

    @Test
    void regionalWeatherCanBeCreatedWithCoordinateAreaAndLimits() throws Exception {
        mockMvc.perform(post("/api/weather")
                        .contentType(APPLICATION_JSON)
                        .content("{\"name\":\"浦东风切变\",\"weatherType\":\"WIND_SHEAR\","
                                + "\"area\":\"310600N1210600E 311800N1212400E 305400N1213600E\","
                                + "\"lowerLimit\":\"S0000\",\"upperLimit\":\"S3000\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("浦东风切变"))
                .andExpect(jsonPath("$.weatherType").value("WIND_SHEAR"))
                .andExpect(jsonPath("$.area").value(org.hamcrest.Matchers.containsString("Polygon")))
                .andExpect(jsonPath("$.lowerLimit").value("S0000"))
                .andExpect(jsonPath("$.upperLimit").value("S3000"));
    }

    @Test
    void unsupportedRegionalWeatherTypeIsRejected() throws Exception {
        mockMvc.perform(post("/api/weather")
                        .contentType(APPLICATION_JSON)
                        .content("{\"name\":\"未知天气\",\"weatherType\":\"HAIL\","
                                + "\"area\":\"310600N1210600E 311800N1212400E 305400N1213600E\","
                                + "\"lowerLimit\":\"S0000\",\"upperLimit\":\"S3000\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("气象类型")));
    }

    @Test
    void weatherLimitsOnlyAcceptSHeightCode() throws Exception {
        mockMvc.perform(post("/api/weather")
                        .contentType(APPLICATION_JSON)
                        .content("{\"name\":\"高度格式错误\",\"weatherType\":\"TURBULENCE\","
                                + "\"area\":\"310600N1210600E 311800N1212400E 305400N1213600E\","
                                + "\"lowerLimit\":\"AGL600\",\"upperLimit\":\"S3000\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("S 高度编码")));
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
