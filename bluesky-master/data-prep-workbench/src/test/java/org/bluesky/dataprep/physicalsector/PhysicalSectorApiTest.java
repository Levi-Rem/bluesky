package org.bluesky.dataprep.physicalsector;

import org.bluesky.dataprep.DataPrepApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = DataPrepApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PhysicalSectorApiTest {
    @Autowired
    private MockMvc mockMvc;

    @Test
    void duplicateNamesAreAllowedAndCoordinatesArePreserved() throws Exception {
        String body = "{\"name\":\"VACC5\",\"sectorType\":\"SECTOR\","
                + "\"compositionMode\":\"COORDINATE\",\"upperLimit\":\"S0920\","
                + "\"lowerLimit\":\"S0450\",\"points\":["
                + "{\"coordinateText\":\"220218N1130000E\"},"
                + "{\"coordinateText\":\"214942N1130000E\"},"
                + "{\"coordinateText\":\"220936N1134154E\"}]}";
        mockMvc.perform(post("/api/physical-sector").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("VACC5"))
                .andExpect(jsonPath("$.points.length()").value(3));
        mockMvc.perform(post("/api/physical-sector").contentType(APPLICATION_JSON)
                        .content(body.replace("S0450", "S0600")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/physical-sector"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2));
    }
}
