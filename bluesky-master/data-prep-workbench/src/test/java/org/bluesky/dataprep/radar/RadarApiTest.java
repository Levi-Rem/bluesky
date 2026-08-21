package org.bluesky.dataprep.radar;

import org.bluesky.dataprep.DataPrepApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = DataPrepApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RadarApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void mixedRadarListShowsSitesAndChannels() throws Exception {
        mockMvc.perform(get("/api/radar"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(org.hamcrest.Matchers.greaterThanOrEqualTo(3)))
                .andExpect(jsonPath("$.items[?(@.code=='RDR-SHA-01')].dataType").value("逻辑雷达"))
                .andExpect(jsonPath("$.items[?(@.code=='RDR-SHA-01')].sac").value(1))
                .andExpect(jsonPath("$.items[?(@.code=='CH-048-01')].dataType").value("CAT048"))
                .andExpect(jsonPath("$.items[?(@.code=='CH-048-01')].networkEndpoint").value("239.1.1.10:5000"))
                .andExpect(jsonPath("$.items[?(@.code=='CH-021-01')].status").value("DISABLED"));
    }

    @Test
    void siteDetailShowsBoundChannelCodes() throws Exception {
        MvcResult list = mockMvc.perform(get("/api/radar-site")).andReturn();
        String siteId = null;
        for (Object item : com.jayway.jsonpath.JsonPath.<java.util.List<Object>>read(
                list.getResponse().getContentAsString(), "$.items")) {
            if ("RDR-SHA-01".equals(((java.util.Map<String, Object>) item).get("code"))) {
                siteId = (String) ((java.util.Map<String, Object>) item).get("id");
            }
        }
        mockMvc.perform(get("/api/radar-site/{id}", siteId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.boundChannelCodes[0]").value("CH-048-01"));
    }

    @Test
    void cat048ChannelRequiresSiteBinding() throws Exception {
        mockMvc.perform(post("/api/asterix-channel")
                        .contentType(APPLICATION_JSON)
                        .content("{\"code\":\"CH-048-T\",\"name\":\"未绑定048\",\"category\":\"CAT048\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("CAT048 通道必须绑定")));
    }

    @Test
    void cat021ChannelWithoutSiteAllowed() throws Exception {
        mockMvc.perform(post("/api/asterix-channel")
                        .contentType(APPLICATION_JSON)
                        .content("{\"code\":\"CH-021-T\",\"name\":\"无站021\",\"category\":\"CAT021\","
                                + "\"edition\":\"2.7\",\"periodMs\":1000,\"transmissionMode\":\"MULTICAST\","
                                + "\"destinationIp\":\"239.1.1.12\",\"destinationPort\":5002}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maximumDatagramBytes").value(1400))
                .andExpect(jsonPath("$.boundSiteIds.length()").value(0));
    }

    @Test
    void cat048ChannelWithValidBindingCreated() throws Exception {
        MvcResult sites = mockMvc.perform(get("/api/radar-site")).andReturn();
        String siteId = null;
        for (Object item : com.jayway.jsonpath.JsonPath.<java.util.List<Object>>read(
                sites.getResponse().getContentAsString(), "$.items")) {
            if ("RDR-SHA-01".equals(((java.util.Map<String, Object>) item).get("code"))) {
                siteId = (String) ((java.util.Map<String, Object>) item).get("id");
            }
        }
        mockMvc.perform(post("/api/asterix-channel")
                        .contentType(APPLICATION_JSON)
                        .content("{\"code\":\"CH-048-T2\",\"name\":\"绑定048\",\"category\":\"CAT048\","
                                + "\"edition\":\"1.32\",\"periodMs\":4000,\"transmissionMode\":\"MULTICAST\","
                                + "\"destinationIp\":\"239.1.1.13\",\"destinationPort\":5003,"
                                + "\"boundSiteIds\":[\"" + siteId + "\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.boundSiteIds[0]").value(siteId));
    }

    @Test
    void bindingToMissingSiteRejected() throws Exception {
        mockMvc.perform(post("/api/asterix-channel")
                        .contentType(APPLICATION_JSON)
                        .content("{\"code\":\"CH-048-T3\",\"name\":\"坏绑定\",\"category\":\"CAT048\","
                                + "\"boundSiteIds\":[\"no-such-site\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("雷达站不存在")));
    }

    @Test
    void invalidSacRangeRejected() throws Exception {
        mockMvc.perform(post("/api/radar-site")
                        .contentType(APPLICATION_JSON)
                        .content("{\"code\":\"RDR-T1\",\"name\":\"坏SAC\",\"sac\":999,\"sic\":20,"
                                + "\"longitude\":121.5,\"latitude\":31.1}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void cat048BoundSiteCannotBeDeleted() throws Exception {
        MvcResult list = mockMvc.perform(get("/api/radar-site").param("size", "200")).andReturn();
        String siteId = null;
        int revision = 0;
        for (Object item : com.jayway.jsonpath.JsonPath.<java.util.List<Object>>read(
                list.getResponse().getContentAsString(), "$.items")) {
            java.util.Map<String, Object> row = (java.util.Map<String, Object>) item;
            if ("RDR-SHA-01".equals(row.get("code"))) {
                siteId = (String) row.get("id");
                revision = ((Number) row.get("revision")).intValue();
            }
        }

        mockMvc.perform(delete("/api/radar-site/{id}", siteId)
                        .param("revision", String.valueOf(revision)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("CAT048")));
    }
}
