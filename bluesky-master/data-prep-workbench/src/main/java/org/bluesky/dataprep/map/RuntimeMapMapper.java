package org.bluesky.dataprep.map;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface RuntimeMapMapper {

    @Select("SELECT id AS \"id\", code AS \"code\", name AS \"name\", "
            + "point_type AS \"pointType\", elevation_m AS \"elevationMeters\", "
            + "longitude AS \"longitude\", latitude AS \"latitude\", 'navigation-point' AS \"entityKind\" "
            + "FROM navigation_point WHERE deleted = FALSE AND status = 'ENABLED' "
            + "AND UPPER(point_type) IN ('FIX','REPORT','WAYPOINT','AIRPORT','AIRPORT_I',"
            + "'VOR','NDB','DME','VOR_DME','VORDME','ILS') "
            + "UNION ALL "
            + "SELECT id, code, name, 'AIRPORT', elevation_m, longitude, latitude, 'airport' "
            + "FROM airport WHERE deleted = FALSE AND status = 'ENABLED' "
            + "ORDER BY 2, 1")
    List<Map<String, Object>> selectWaypoints();

    @Select("SELECT id AS \"id\", code AS \"code\", name AS \"name\", "
            + "airway_direction AS \"airwayDirection\" "
            + "FROM airway WHERE deleted = FALSE AND status = 'ENABLED' ORDER BY code, id")
    List<Map<String, Object>> selectAirways();

    @Select("SELECT s.airway_id AS \"airwayId\", s.order_no AS \"orderNo\", "
            + "COALESCE(sap.id, CASE WHEN sp.status = 'ENABLED' AND UPPER(sp.point_type) IN "
            + "('FIX','REPORT','WAYPOINT','AIRPORT','AIRPORT_I','VOR','NDB','DME','VOR_DME','VORDME','ILS') "
            + "THEN sp.id END) AS \"startPointId\", "
            + "COALESCE(sap.code, CASE WHEN sp.status = 'ENABLED' THEN sp.code END) AS \"startPointCode\", "
            + "COALESCE(sap.longitude, CASE WHEN sp.status = 'ENABLED' THEN sp.longitude END) AS \"startLongitude\", "
            + "COALESCE(sap.latitude, CASE WHEN sp.status = 'ENABLED' THEN sp.latitude END) AS \"startLatitude\", "
            + "COALESCE(eap.id, CASE WHEN ep.status = 'ENABLED' AND UPPER(ep.point_type) IN "
            + "('FIX','REPORT','WAYPOINT','AIRPORT','AIRPORT_I','VOR','NDB','DME','VOR_DME','VORDME','ILS') "
            + "THEN ep.id END) AS \"endPointId\", "
            + "COALESCE(eap.code, CASE WHEN ep.status = 'ENABLED' THEN ep.code END) AS \"endPointCode\", "
            + "COALESCE(eap.longitude, CASE WHEN ep.status = 'ENABLED' THEN ep.longitude END) AS \"endLongitude\", "
            + "COALESCE(eap.latitude, CASE WHEN ep.status = 'ENABLED' THEN ep.latitude END) AS \"endLatitude\", "
            + "s.segment_direction AS \"segmentDirection\" "
            + "FROM airway_segment s JOIN airway a ON a.id = s.airway_id "
            + "LEFT JOIN navigation_point sp ON sp.id = s.start_point_id AND sp.deleted = FALSE "
            + "LEFT JOIN airport sap ON sp.status = 'DISABLED' "
            + "AND UPPER(sp.point_type) IN ('AIRPORT','AIRPORT_I') "
            + "AND sap.deleted = FALSE AND sap.status = 'ENABLED' "
            + "AND UPPER(TRIM(sap.code)) = UPPER(TRIM(sp.code)) "
            + "LEFT JOIN navigation_point ep ON ep.id = s.end_point_id AND ep.deleted = FALSE "
            + "LEFT JOIN airport eap ON ep.status = 'DISABLED' "
            + "AND UPPER(ep.point_type) IN ('AIRPORT','AIRPORT_I') "
            + "AND eap.deleted = FALSE AND eap.status = 'ENABLED' "
            + "AND UPPER(TRIM(eap.code)) = UPPER(TRIM(ep.code)) "
            + "WHERE s.deleted = FALSE AND a.deleted = FALSE AND a.status = 'ENABLED' "
            + "ORDER BY s.airway_id, s.order_no, s.id")
    List<Map<String, Object>> selectAirwaySegments();

    @Select("SELECT id AS \"id\", name AS \"name\" FROM physical_sector "
            + "WHERE deleted = FALSE ORDER BY name, id")
    List<Map<String, Object>> selectPhysicalSectors();

    @Select("SELECT p.physical_sector_id AS \"sectorId\", p.order_no AS \"seq\", "
            + "p.longitude AS \"longitude\", p.latitude AS \"latitude\" "
            + "FROM physical_sector_point p JOIN physical_sector s ON s.id = p.physical_sector_id "
            + "WHERE p.deleted = FALSE AND s.deleted = FALSE ORDER BY p.physical_sector_id, p.order_no")
    List<Map<String, Object>> selectPhysicalSectorPoints();

    @Select("SELECT p.id AS \"id\", w.code AS \"code\", w.name AS \"name\", "
            + "p.longitude AS \"longitude\", p.latitude AS \"latitude\" "
            + "FROM wind_field_point p JOIN wind_field w ON w.id = p.wind_field_id "
            + "WHERE p.deleted = FALSE AND w.deleted = FALSE AND w.status = 'ENABLED' "
            + "ORDER BY w.code, p.order_no, p.id")
    List<Map<String, Object>> selectWindPoints();

    @Select("SELECT id AS \"id\", code AS \"code\", name AS \"name\", "
            + "CAST(boundary AS CHAR(16384)) AS \"boundary\" "
            + "FROM significant_weather_area "
            + "WHERE deleted = FALSE AND status = 'ENABLED' ORDER BY code, id")
    List<Map<String, Object>> selectSignificantWeatherAreas();
}
