package org.bluesky.dataprep.map;

import org.bluesky.dataprep.DataPrepApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = DataPrepApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class RuntimeMapApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void returnsFourRuntimeLayersInStableOrder() throws Exception {
        mockMvc.perform(get("/api/map/runtime-layers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").isNumber())
                .andExpect(jsonPath("$.layers.length()").value(4))
                .andExpect(jsonPath("$.layers[0].category").value("WAYPOINT"))
                .andExpect(jsonPath("$.layers[1].category").value("AIRWAY"))
                .andExpect(jsonPath("$.layers[2].category").value("PHYSICAL_SECTOR"))
                .andExpect(jsonPath("$.layers[3].category").value("WEATHER"));
    }

    @Test
    void excludesDisabledWaypointsAndAirways() throws Exception {
        jdbc.update("UPDATE navigation_point SET status='DISABLED' WHERE id='seed-nav-and'");
        jdbc.update("UPDATE airway SET status='DISABLED' WHERE id='seed-aw-b221'");

        mockMvc.perform(get("/api/map/runtime-layers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.layers[0].features[*].code", hasItem("PUD")))
                .andExpect(jsonPath("$.layers[0].features[*].code", not(hasItem("AND"))))
                .andExpect(jsonPath("$.layers[1].features[*].code", hasItem("A593")))
                .andExpect(jsonPath("$.layers[1].features[*].code", not(hasItem("B221"))));
    }

    @Test
    void physicalSectorBecomesClosedPolygonAndUsesName() throws Exception {
        jdbc.update("INSERT INTO physical_sector "
                + "(id,name,sector_type,composition_mode,upper_limit,lower_limit,source_type,revision,deleted,created_by,updated_by) "
                + "VALUES ('runtime-sector','运行扇区','SECTOR','COORDINATE','S3000','S0000','MANUAL',0,FALSE,'test','test')");
        insertSectorPoint("runtime-sector-p1", 0, 121.0, 31.0);
        insertSectorPoint("runtime-sector-p2", 1, 122.0, 31.0);
        insertSectorPoint("runtime-sector-p3", 2, 121.5, 32.0);

        mockMvc.perform(get("/api/map/runtime-layers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.layers[2].count").value(1))
                .andExpect(jsonPath("$.layers[2].features[0].featureType").value("PHYSICAL_SECTOR"))
                .andExpect(jsonPath("$.layers[2].features[0].name").value("运行扇区"))
                .andExpect(jsonPath("$.layers[2].features[0].geometry.type").value("Polygon"))
                .andExpect(jsonPath("$.layers[2].features[0].geometry.coordinates[0].length()").value(4))
                .andExpect(jsonPath("$.layers[2].features[0].geometry.coordinates[0][0][0]").value(121.0))
                .andExpect(jsonPath("$.layers[2].features[0].geometry.coordinates[0][3][0]").value(121.0));
    }

    @Test
    void weatherCombinesEnabledWindPointsAndSignificantAreasWithoutTimeFiltering() throws Exception {
        mockMvc.perform(get("/api/map/runtime-layers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.layers[3].features[*].featureType", hasItem("WIND_FIELD_POINT")))
                .andExpect(jsonPath("$.layers[3].features[*].featureType", hasItem("SIGNIFICANT_WEATHER_AREA")))
                .andExpect(jsonPath("$.layers[3].features[*].code", hasItem("CB-07")))
                .andExpect(jsonPath("$.layers[3].features[*].code", not(hasItem("MET-ZSPD"))));
    }

    private void insertSectorPoint(String id, int order, double longitude, double latitude) {
        jdbc.update("INSERT INTO physical_sector_point "
                        + "(id,physical_sector_id,order_no,coordinate_text,longitude,latitude,deleted) "
                        + "VALUES (?, 'runtime-sector', ?, ?, ?, ?, FALSE)",
                id, order, latitude + "," + longitude, longitude, latitude);
    }
}
