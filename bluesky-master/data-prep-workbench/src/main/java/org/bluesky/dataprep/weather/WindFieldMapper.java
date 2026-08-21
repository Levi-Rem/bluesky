package org.bluesky.dataprep.weather;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface WindFieldMapper {

    String COLS = "id, code, name, wind_field_type, wind_direction_deg, wind_speed_ms, boundary, "
            + "effective_from, effective_to, status, source_type, source_reference, revision, "
            + "deleted, created_at, created_by, updated_at, updated_by ";

    @Select("SELECT " + COLS + "FROM wind_field WHERE deleted = FALSE ORDER BY code LIMIT #{size} OFFSET #{offset}")
    List<WindFieldRow> selectPage(@Param("offset") int offset, @Param("size") int size);

    @Select("SELECT COUNT(*) FROM wind_field WHERE deleted = FALSE")
    long count();

    @Select("SELECT " + COLS + "FROM wind_field WHERE id = #{id} AND deleted = FALSE")
    WindFieldRow findById(String id);

    @Select("SELECT COUNT(*) FROM wind_field WHERE code = #{code} AND deleted = FALSE AND id <> #{excludeId}")
    int countByCode(@Param("code") String code, @Param("excludeId") String excludeId);

    @Insert("INSERT INTO wind_field (id, code, name, wind_field_type, wind_direction_deg, wind_speed_ms, "
            + "boundary, effective_from, effective_to, status, source_type, source_reference, revision, "
            + "deleted, created_by, updated_by) VALUES (#{id}, #{code}, #{name}, #{windFieldType}, "
            + "#{windDirectionDeg}, #{windSpeedMs}, #{boundary}, #{effectiveFrom}, #{effectiveTo}, "
            + "#{status}, #{sourceType}, #{sourceReference}, 0, FALSE, #{createdBy}, #{updatedBy})")
    int insert(WindFieldRow row);

    @Update("UPDATE wind_field SET code = #{code}, name = #{name}, wind_field_type = #{windFieldType}, "
            + "wind_direction_deg = #{windDirectionDeg}, wind_speed_ms = #{windSpeedMs}, boundary = #{boundary}, "
            + "effective_from = #{effectiveFrom}, effective_to = #{effectiveTo}, status = #{status}, "
            + "source_reference = #{sourceReference}, updated_by = #{updatedBy}, revision = revision + 1 "
            + "WHERE id = #{id} AND revision = #{revision} AND deleted = FALSE")
    int update(WindFieldRow row);

    @Update("UPDATE wind_field SET deleted = TRUE, revision = revision + 1 WHERE id = #{id} AND revision = #{revision} AND deleted = FALSE")
    int markDeleted(@Param("id") String id, @Param("revision") int revision);

    @Update("UPDATE wind_field SET status = #{status}, revision = revision + 1 WHERE id = #{id} AND revision = #{revision} AND deleted = FALSE")
    int updateStatus(@Param("id") String id, @Param("revision") int revision, @Param("status") String status);

    @Select("SELECT id, wind_field_id, order_no, longitude, latitude, altitude_m, wind_direction_deg, "
            + "wind_speed_ms FROM wind_field_point WHERE wind_field_id = #{windFieldId} AND deleted = FALSE "
            + "ORDER BY order_no")
    List<WindPointRow> findPoints(String windFieldId);

    @Insert("INSERT INTO wind_field_point (id, wind_field_id, order_no, longitude, latitude, altitude_m, "
            + "wind_direction_deg, wind_speed_ms) VALUES (#{id}, #{windFieldId}, #{orderNo}, #{longitude}, "
            + "#{latitude}, #{altitudeM}, #{windDirectionDeg}, #{windSpeedMs})")
    int insertPoint(WindPointRow row);

    @Update("UPDATE wind_field_point SET deleted = TRUE WHERE wind_field_id = #{windFieldId}")
    int markPointsDeleted(String windFieldId);
}
