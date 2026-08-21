package org.bluesky.dataprep.airway;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface AirwayMapper {

    String COLS = "id, code, name, airway_direction, lower_value, lower_reference, upper_value, "
            + "upper_reference, status, source_type, source_reference, revision, deleted, "
            + "created_at, created_by, updated_at, updated_by ";

    @Select("SELECT " + COLS + "FROM airway WHERE deleted = FALSE ORDER BY code LIMIT #{size} OFFSET #{offset}")
    List<AirwayRow> selectPage(@Param("offset") int offset, @Param("size") int size);

    @Select("SELECT COUNT(*) FROM airway WHERE deleted = FALSE")
    long count();

    @Select("SELECT " + COLS + "FROM airway WHERE id = #{id} AND deleted = FALSE")
    AirwayRow findById(String id);

    @Select("SELECT COUNT(*) FROM airway WHERE code = #{code} AND deleted = FALSE AND id <> #{excludeId}")
    int countByCode(@Param("code") String code, @Param("excludeId") String excludeId);

    @Insert("INSERT INTO airway (id, code, name, airway_direction, lower_value, lower_reference, "
            + "upper_value, upper_reference, status, source_type, source_reference, revision, deleted, "
            + "created_by, updated_by) VALUES (#{id}, #{code}, #{name}, #{airwayDirection}, #{lowerValue}, "
            + "#{lowerReference}, #{upperValue}, #{upperReference}, #{status}, #{sourceType}, "
            + "#{sourceReference}, 0, FALSE, #{createdBy}, #{updatedBy})")
    int insert(AirwayRow row);

    @Update("UPDATE airway SET code = #{code}, name = #{name}, airway_direction = #{airwayDirection}, "
            + "lower_value = #{lowerValue}, lower_reference = #{lowerReference}, upper_value = #{upperValue}, "
            + "upper_reference = #{upperReference}, status = #{status}, source_reference = #{sourceReference}, "
            + "updated_by = #{updatedBy}, revision = revision + 1 "
            + "WHERE id = #{id} AND revision = #{revision} AND deleted = FALSE")
    int update(AirwayRow row);

    @Update("UPDATE airway SET deleted = TRUE, revision = revision + 1 WHERE id = #{id} AND revision = #{revision} AND deleted = FALSE")
    int markDeleted(@Param("id") String id, @Param("revision") int revision);

    @Update("UPDATE airway SET status = #{status}, revision = revision + 1 WHERE id = #{id} AND revision = #{revision} AND deleted = FALSE")
    int updateStatus(@Param("id") String id, @Param("revision") int revision, @Param("status") String status);

    // ---- 航段子表（JOIN 解析起止点编码）----

    @Select("SELECT s.id, s.airway_id, s.order_no, s.start_point_id, s.end_point_id, s.segment_direction, "
            + "s.lower_value, s.lower_reference, s.upper_value, s.upper_reference, "
            + "sp.code AS start_point_code, ep.code AS end_point_code "
            + "FROM airway_segment s "
            + "LEFT JOIN navigation_point sp ON sp.id = s.start_point_id "
            + "LEFT JOIN navigation_point ep ON ep.id = s.end_point_id "
            + "WHERE s.airway_id = #{airwayId} AND s.deleted = FALSE ORDER BY s.order_no")
    List<AirwaySegmentRow> findSegments(String airwayId);

    @Insert("INSERT INTO airway_segment (id, airway_id, order_no, start_point_id, end_point_id, "
            + "segment_direction, lower_value, lower_reference, upper_value, upper_reference) VALUES "
            + "(#{id}, #{airwayId}, #{orderNo}, #{startPointId}, #{endPointId}, #{segmentDirection}, "
            + "#{lowerValue}, #{lowerReference}, #{upperValue}, #{upperReference})")
    int insertSegment(AirwaySegmentRow row);

    @Update("UPDATE airway_segment SET deleted = TRUE WHERE airway_id = #{airwayId}")
    int markSegmentsDeleted(String airwayId);
}
