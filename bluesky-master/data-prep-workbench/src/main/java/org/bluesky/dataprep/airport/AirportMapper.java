package org.bluesky.dataprep.airport;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface AirportMapper {

    String COLS = "id, code, name, icao, iata, country, airport_grade, max_runway_length_m, "
            + "longitude, latitude, elevation_m, status, source_type, source_reference, revision, "
            + "deleted, created_at, created_by, updated_at, updated_by ";

    @Select("SELECT " + COLS + "FROM airport WHERE deleted = FALSE ORDER BY code LIMIT #{size} OFFSET #{offset}")
    List<AirportRow> selectPage(@Param("offset") int offset, @Param("size") int size);

    @Select("SELECT COUNT(*) FROM airport WHERE deleted = FALSE")
    long count();

    @Select("SELECT " + COLS + "FROM airport WHERE id = #{id} AND deleted = FALSE")
    AirportRow findById(String id);

    @Select("SELECT COUNT(*) FROM airport WHERE code = #{code} AND deleted = FALSE AND id <> #{excludeId}")
    int countByCode(@Param("code") String code, @Param("excludeId") String excludeId);

    @Insert("INSERT INTO airport (id, code, name, icao, iata, country, airport_grade, max_runway_length_m, "
            + "longitude, latitude, elevation_m, status, source_type, source_reference, revision, deleted, "
            + "created_by, updated_by) VALUES (#{id}, #{code}, #{name}, #{icao}, #{iata}, #{country}, "
            + "#{airportGrade}, #{maxRunwayLengthM}, #{longitude}, #{latitude}, #{elevationM}, #{status}, "
            + "#{sourceType}, #{sourceReference}, 0, FALSE, #{createdBy}, #{updatedBy})")
    int insert(AirportRow row);

    @Update("UPDATE airport SET code = #{code}, name = #{name}, icao = #{icao}, iata = #{iata}, "
            + "country = #{country}, airport_grade = #{airportGrade}, max_runway_length_m = #{maxRunwayLengthM}, "
            + "longitude = #{longitude}, latitude = #{latitude}, elevation_m = #{elevationM}, status = #{status}, "
            + "source_reference = #{sourceReference}, updated_by = #{updatedBy}, revision = revision + 1 "
            + "WHERE id = #{id} AND revision = #{revision} AND deleted = FALSE")
    int update(AirportRow row);

    @Update("UPDATE airport SET deleted = TRUE, revision = revision + 1 WHERE id = #{id} AND revision = #{revision} AND deleted = FALSE")
    int markDeleted(@Param("id") String id, @Param("revision") int revision);

    @Update("UPDATE airport SET status = #{status}, revision = revision + 1 WHERE id = #{id} AND revision = #{revision} AND deleted = FALSE")
    int updateStatus(@Param("id") String id, @Param("revision") int revision, @Param("status") String status);

    // ---- 跑道子表 ----

    @Select("SELECT id, airport_id, designation, thr1_longitude, thr1_latitude, thr2_longitude, thr2_latitude, "
            + "length_m, width_m, true_heading_deg, magnetic_heading_deg, surface, runway_status, order_no "
            + "FROM runway WHERE airport_id = #{airportId} AND deleted = FALSE ORDER BY order_no")
    List<RunwayRow> findRunways(String airportId);

    @Insert("INSERT INTO runway (id, airport_id, designation, thr1_longitude, thr1_latitude, thr2_longitude, "
            + "thr2_latitude, length_m, width_m, true_heading_deg, magnetic_heading_deg, surface, runway_status, order_no) "
            + "VALUES (#{id}, #{airportId}, #{designation}, #{thr1Longitude}, #{thr1Latitude}, #{thr2Longitude}, "
            + "#{thr2Latitude}, #{lengthM}, #{widthM}, #{trueHeadingDeg}, #{magneticHeadingDeg}, #{surface}, "
            + "#{runwayStatus}, #{orderNo})")
    int insertRunway(RunwayRow row);

    @Update("UPDATE runway SET deleted = TRUE WHERE airport_id = #{airportId}")
    int markRunwaysDeleted(String airportId);
}
