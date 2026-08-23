package org.bluesky.dataprep.map;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface RuntimeMapMapper {

    @Select("SELECT id AS \"id\", code AS \"code\", name AS \"name\", "
            + "longitude AS \"longitude\", latitude AS \"latitude\" "
            + "FROM navigation_point WHERE deleted = FALSE AND status = 'ENABLED' ORDER BY code, id")
    List<Map<String, Object>> selectWaypoints();

    @Select("SELECT id AS \"id\", code AS \"code\", name AS \"name\" "
            + "FROM airway WHERE deleted = FALSE AND status = 'ENABLED' ORDER BY code, id")
    List<Map<String, Object>> selectAirways();

    @Select("SELECT s.airway_id AS \"airwayId\", s.order_no * 2 AS \"seq\", "
            + "sp.longitude AS \"longitude\", sp.latitude AS \"latitude\" "
            + "FROM airway_segment s JOIN navigation_point sp ON sp.id = s.start_point_id "
            + "JOIN airway a ON a.id = s.airway_id "
            + "WHERE s.deleted = FALSE AND sp.deleted = FALSE AND a.deleted = FALSE AND a.status = 'ENABLED' "
            + "UNION ALL "
            + "SELECT s.airway_id, s.order_no * 2 + 1, ep.longitude, ep.latitude "
            + "FROM airway_segment s JOIN navigation_point ep ON ep.id = s.end_point_id "
            + "JOIN airway a ON a.id = s.airway_id "
            + "WHERE s.deleted = FALSE AND ep.deleted = FALSE AND a.deleted = FALSE AND a.status = 'ENABLED' "
            + "ORDER BY 1, 2")
    List<Map<String, Object>> selectAirwayVertices();

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
            + "CAST(boundary AS VARCHAR(16384)) AS \"boundary\" "
            + "FROM significant_weather_area "
            + "WHERE deleted = FALSE AND status = 'ENABLED' ORDER BY code, id")
    List<Map<String, Object>> selectSignificantWeatherAreas();
}
