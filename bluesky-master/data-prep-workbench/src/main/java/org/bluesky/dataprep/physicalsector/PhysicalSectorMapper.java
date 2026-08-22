package org.bluesky.dataprep.physicalsector;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface PhysicalSectorMapper {
    String COLS = "id, name, sector_type, composition_mode, upper_limit, lower_limit, source_subtype, "
            + "source_flag, source_type, source_reference, revision, deleted, created_at, created_by, "
            + "updated_at, updated_by ";

    @Select("SELECT " + COLS + "FROM physical_sector WHERE deleted = FALSE "
            + "ORDER BY name, lower_limit, upper_limit, id LIMIT #{size} OFFSET #{offset}")
    List<PhysicalSectorRow> selectPage(@Param("offset") int offset, @Param("size") int size);

    @Select("SELECT COUNT(*) FROM physical_sector WHERE deleted = FALSE")
    long count();

    @Select("SELECT " + COLS + "FROM physical_sector WHERE id = #{id} AND deleted = FALSE")
    PhysicalSectorRow findById(String id);

    @Select("SELECT id, physical_sector_id, order_no, nav_point_id, point_name, coordinate_text, longitude, latitude "
            + "FROM physical_sector_point WHERE physical_sector_id = #{sectorId} AND deleted = FALSE ORDER BY order_no")
    List<PhysicalSectorPointRow> selectPoints(String sectorId);

    @Insert("INSERT INTO physical_sector (id, name, sector_type, composition_mode, upper_limit, lower_limit, "
            + "source_subtype, source_flag, source_type, source_reference, revision, deleted, created_by, updated_by) "
            + "VALUES (#{id}, #{name}, #{sectorType}, #{compositionMode}, #{upperLimit}, #{lowerLimit}, "
            + "#{sourceSubtype}, #{sourceFlag}, #{sourceType}, #{sourceReference}, 0, FALSE, #{createdBy}, #{updatedBy})")
    int insert(PhysicalSectorRow row);

    @Insert("INSERT INTO physical_sector_point (id, physical_sector_id, order_no, nav_point_id, point_name, "
            + "coordinate_text, longitude, latitude, deleted) VALUES (#{id}, #{physicalSectorId}, #{orderNo}, "
            + "#{navPointId}, #{pointName}, #{coordinateText}, #{longitude}, #{latitude}, FALSE)")
    int insertPoint(PhysicalSectorPointRow point);

    @Update("UPDATE physical_sector SET name = #{name}, sector_type = #{sectorType}, "
            + "composition_mode = #{compositionMode}, upper_limit = #{upperLimit}, lower_limit = #{lowerLimit}, "
            + "source_subtype = #{sourceSubtype}, source_flag = #{sourceFlag}, source_reference = #{sourceReference}, "
            + "updated_by = #{updatedBy}, updated_at = CURRENT_TIMESTAMP(3), revision = revision + 1 "
            + "WHERE id = #{id} AND revision = #{revision} AND deleted = FALSE")
    int update(PhysicalSectorRow row);

    @Update("UPDATE physical_sector_point SET deleted = TRUE, updated_at = CURRENT_TIMESTAMP(3) "
            + "WHERE physical_sector_id = #{sectorId} AND deleted = FALSE")
    int markPointsDeleted(String sectorId);

    @Update("UPDATE physical_sector SET deleted = TRUE, updated_at = CURRENT_TIMESTAMP(3), revision = revision + 1 "
            + "WHERE id = #{id} AND revision = #{revision} AND deleted = FALSE")
    int markDeleted(@Param("id") String id, @Param("revision") int revision);
}
