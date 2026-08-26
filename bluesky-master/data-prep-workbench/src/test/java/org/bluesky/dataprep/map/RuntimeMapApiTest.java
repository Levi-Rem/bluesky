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
                .andExpect(jsonPath("$.revision").doesNotExist())
                .andExpect(jsonPath("$.layers.length()").value(4))
                .andExpect(jsonPath("$.layers[0].category").value("WAYPOINT"))
                .andExpect(jsonPath("$.layers[1].category").value("AIRWAY"))
                .andExpect(jsonPath("$.layers[2].category").value("PHYSICAL_SECTOR"))
                .andExpect(jsonPath("$.layers[3].category").value("WEATHER"));
    }

    @Test
    void excludesDisabledWaypointsAndAirways() throws Exception {
        jdbc.update("INSERT INTO navigation_point "
                + "(id,code,name,point_type,longitude,latitude,status,source_type,deleted) "
                + "VALUES ('runtime-disabled','OFF','停用点','FIX',120,30,'DISABLED','MANUAL',FALSE)");
        jdbc.update("UPDATE airway SET status='DISABLED' WHERE id='seed-aw-b221'");

        mockMvc.perform(get("/api/map/runtime-layers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.layers[0].features[*].code", hasItem("PUD")))
                .andExpect(jsonPath("$.layers[0].features[*].code", not(hasItem("OFF"))))
                .andExpect(jsonPath("$.layers[1].features[*].code", hasItem("A593")))
                .andExpect(jsonPath("$.layers[1].features[*].code", not(hasItem("B221"))));
    }

    @Test
    void returnsStandardNavigationTypesAndAirportsWithEngineMetadata() throws Exception {
        jdbc.update("INSERT INTO navigation_point "
                + "(id,code,name,point_type,longitude,latitude,elevation_m,status,source_type,deleted) "
                + "VALUES ('runtime-other','OTHER1','排除点','OTHER',120,30,10,'ENABLED','MANUAL',FALSE)");

        mockMvc.perform(get("/api/map/runtime-layers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.layers[0].features[*].code", hasItem("PUD")))
                .andExpect(jsonPath("$.layers[0].features[*].code", hasItem("ZSPD")))
                .andExpect(jsonPath("$.layers[0].features[*].code", not(hasItem("OTHER1"))))
                .andExpect(jsonPath("$.layers[0].features[?(@.code=='PUD')].pointType", hasItem("VOR")))
                .andExpect(jsonPath("$.layers[0].features[?(@.code=='PUD')].elevationMeters", hasItem(4)))
                .andExpect(jsonPath("$.layers[0].features[?(@.code=='ZSPD')].pointType", hasItem("AIRPORT")));
    }

    @Test
    void returnsOrderedAirwayPointReferencesAndDirections() throws Exception {
        mockMvc.perform(get("/api/map/runtime-layers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.layers[1].features[?(@.code=='A593')].airwayDirection",
                        hasItem("BOTH")))
                .andExpect(jsonPath("$.layers[1].features[?(@.code=='A593')].pointIds[0]",
                        hasItem("seed-nav-pud")))
                .andExpect(jsonPath("$.layers[1].features[?(@.code=='A593')].pointCodes[0]",
                        hasItem("PUD")))
                .andExpect(jsonPath("$.layers[1].features[?(@.code=='A593')].pointCodes[1]",
                        hasItem("SASAN")))
                .andExpect(jsonPath("$.layers[1].features[?(@.code=='A593')].segmentDirections[0]",
                        hasItem("BOTH")));
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
        jdbc.update("UPDATE significant_weather_area SET valid_from=TIMESTAMP '2000-01-01 00:00:00', "
                + "valid_to=TIMESTAMP '2000-01-02 00:00:00' WHERE id='seed-cb-07'");

        mockMvc.perform(get("/api/map/runtime-layers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.layers[3].features[*].featureType", hasItem("WIND_FIELD_POINT")))
                .andExpect(jsonPath("$.layers[3].features[*].featureType", hasItem("SIGNIFICANT_WEATHER_AREA")))
                .andExpect(jsonPath("$.layers[3].features[*].code", hasItem("CB-07")))
                .andExpect(jsonPath("$.layers[3].features[*].code", not(hasItem("MET-ZSPD"))));
    }

    @Test
    void rejectsSnapshotWhenAnyNavigationPointIsInvalid() throws Exception {
        jdbc.update("UPDATE navigation_point SET latitude=999 WHERE id='seed-nav-sasan'");

        mockMvc.perform(get("/api/map/runtime-layers"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INVALID_RUNTIME_NAV_DATA"));
    }

    @Test
    void normalizesAllSupportedLegacyPointTypeAliases() throws Exception {
        jdbc.update("INSERT INTO navigation_point "
                + "(id,code,name,point_type,longitude,latitude,status,source_type,deleted) VALUES "
                + "('runtime-report','RPT1','报告点','REPORT',120,30,'ENABLED','MANUAL',FALSE),"
                + "('runtime-airport-i','APT-I','机场点','AIRPORT_I',121,31,'ENABLED','MANUAL',FALSE),"
                + "('runtime-vordme','VDM1','导航台','VORDME',122,32,'ENABLED','MANUAL',FALSE)");

        mockMvc.perform(get("/api/map/runtime-layers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.layers[0].features[?(@.code=='RPT1')].pointType").value("WAYPOINT"))
                .andExpect(jsonPath("$.layers[0].features[?(@.code=='APT-I')].pointType").value("AIRPORT"))
                .andExpect(jsonPath("$.layers[0].features[?(@.code=='VDM1')].pointType").value("VOR_DME"));
    }

    @Test
    void rejectsSnapshotWhenAirwayReferencesDeletedWaypoint() throws Exception {
        jdbc.update("UPDATE navigation_point SET deleted=TRUE WHERE id='seed-nav-sasan'");

        mockMvc.perform(get("/api/map/runtime-layers"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INVALID_RUNTIME_NAV_DATA"));
    }

    @Test
    void rejectsSnapshotWhenNavigationCodesOnlyDifferByCase() throws Exception {
        jdbc.update("INSERT INTO airport "
                + "(id,code,name,longitude,latitude,status,source_type,deleted) "
                + "VALUES ('runtime-duplicate','pud','重复机场',120,30,'ENABLED','MANUAL',FALSE)");

        mockMvc.perform(get("/api/map/runtime-layers"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INVALID_RUNTIME_NAV_DATA"));
    }

    @Test
    void mapsHistoricalDisabledAirportSegmentReferenceToCanonicalAirport() throws Exception {
        jdbc.update("INSERT INTO airport "
                + "(id,code,name,longitude,latitude,status,source_type,deleted) "
                + "VALUES ('runtime-canonical-airport','TAPT','规范机场',120.5,30.5,'ENABLED','MANUAL',FALSE)");
        jdbc.update("INSERT INTO navigation_point "
                + "(id,code,name,point_type,longitude,latitude,status,source_type,deleted) "
                + "VALUES ('runtime-legacy-airport','TAPT','旧机场点','AIRPORT_I',120,30,'DISABLED','ACCOPS_ASF',FALSE)");
        jdbc.update("INSERT INTO airway "
                + "(id,code,name,airway_direction,status,source_type,deleted) "
                + "VALUES ('runtime-airway-airport','T-AIRPORT','机场航路','TWO_WAY','ENABLED','MANUAL',FALSE)");
        String destinationId = jdbc.queryForObject(
                "SELECT id FROM navigation_point WHERE code='PUD'", String.class);
        jdbc.update("INSERT INTO airway_segment "
                + "(id,airway_id,order_no,start_point_id,end_point_id,segment_direction,deleted) "
                + "VALUES ('runtime-airport-segment','runtime-airway-airport',0,"
                + "'runtime-legacy-airport',?,NULL,FALSE)", destinationId);

        mockMvc.perform(get("/api/map/runtime-layers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.layers[0].features[?(@.code=='TAPT')].featureId")
                        .value("airport:runtime-canonical-airport"))
                .andExpect(jsonPath("$.layers[1].features[?(@.code=='T-AIRPORT')].pointIds[0]")
                        .value("runtime-canonical-airport"))
                .andExpect(jsonPath("$.layers[1].features[?(@.code=='T-AIRPORT')].geometry.coordinates[0][0]")
                        .value(120.5))
                .andExpect(jsonPath("$.layers[1].features[?(@.code=='T-AIRPORT')].segmentDirections[0]")
                        .value("BOTH"));
    }

    private void insertSectorPoint(String id, int order, double longitude, double latitude) {
        jdbc.update("INSERT INTO physical_sector_point "
                        + "(id,physical_sector_id,order_no,coordinate_text,longitude,latitude,deleted) "
                        + "VALUES (?, 'runtime-sector', ?, ?, ?, ?, FALSE)",
                id, order, latitude + "," + longitude, longitude, latitude);
    }
}
