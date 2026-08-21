package org.bluesky.dataprep.airspace;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface AirspaceMapper {

    String COLS = "id, code, name, airspace_type, boundary, lower_value, lower_reference, "
            + "upper_value, upper_reference, valid_from, valid_to, status, source_type, "
            + "source_reference, revision, deleted, created_at, created_by, updated_at, updated_by ";

    @Select("SELECT " + COLS + "FROM airspace WHERE deleted = FALSE ORDER BY code LIMIT #{size} OFFSET #{offset}")
    List<AirspaceRow> selectPage(@Param("offset") int offset, @Param("size") int size);

    @Select("SELECT COUNT(*) FROM airspace WHERE deleted = FALSE")
    long count();

    @Select("SELECT " + COLS + "FROM airspace WHERE id = #{id} AND deleted = FALSE")
    AirspaceRow findById(String id);

    @Select("SELECT COUNT(*) FROM airspace WHERE code = #{code} AND deleted = FALSE AND id <> #{excludeId}")
    int countByCode(@Param("code") String code, @Param("excludeId") String excludeId);

    @Insert("INSERT INTO airspace (id, code, name, airspace_type, boundary, lower_value, lower_reference, "
            + "upper_value, upper_reference, valid_from, valid_to, status, source_type, source_reference, "
            + "revision, deleted, created_by, updated_by) VALUES (#{id}, #{code}, #{name}, #{airspaceType}, "
            + "#{boundary}, #{lowerValue}, #{lowerReference}, #{upperValue}, #{upperReference}, #{validFrom}, "
            + "#{validTo}, #{status}, #{sourceType}, #{sourceReference}, 0, FALSE, #{createdBy}, #{updatedBy})")
    int insert(AirspaceRow row);

    @Update("UPDATE airspace SET code = #{code}, name = #{name}, airspace_type = #{airspaceType}, "
            + "boundary = #{boundary}, lower_value = #{lowerValue}, lower_reference = #{lowerReference}, "
            + "upper_value = #{upperValue}, upper_reference = #{upperReference}, valid_from = #{validFrom}, "
            + "valid_to = #{validTo}, status = #{status}, source_reference = #{sourceReference}, "
            + "updated_by = #{updatedBy}, revision = revision + 1 "
            + "WHERE id = #{id} AND revision = #{revision} AND deleted = FALSE")
    int update(AirspaceRow row);

    @Update("UPDATE airspace SET deleted = TRUE, revision = revision + 1 WHERE id = #{id} AND revision = #{revision} AND deleted = FALSE")
    int markDeleted(@Param("id") String id, @Param("revision") int revision);

    @Update("UPDATE airspace SET status = #{status}, revision = revision + 1 WHERE id = #{id} AND revision = #{revision} AND deleted = FALSE")
    int updateStatus(@Param("id") String id, @Param("revision") int revision, @Param("status") String status);
}
