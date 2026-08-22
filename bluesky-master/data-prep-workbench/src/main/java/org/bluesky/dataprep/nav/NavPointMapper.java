package org.bluesky.dataprep.nav;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface NavPointMapper {

    String COLS = "id, code, name, point_type, longitude, latitude, elevation_m, frequency_mhz, "
            + "magnetic_variation_deg, description, source_point_type, coordinate_text, relevant_flag, "
            + "applicable_airports, pilot_flag, dti_flag, tfm_flag, status, source_type, source_reference, revision, "
            + "deleted, created_at, created_by, updated_at, updated_by ";

    @Select("SELECT " + COLS + "FROM navigation_point WHERE deleted = FALSE ORDER BY code LIMIT #{size} OFFSET #{offset}")
    List<NavPointRow> selectPage(@Param("offset") int offset, @Param("size") int size);

    @Select("SELECT COUNT(*) FROM navigation_point WHERE deleted = FALSE")
    long count();

    @Select("SELECT " + COLS + "FROM navigation_point WHERE id = #{id} AND deleted = FALSE")
    NavPointRow findById(String id);

    @Select("SELECT COUNT(*) FROM navigation_point WHERE code = #{code} AND deleted = FALSE AND id <> #{excludeId}")
    int countByCode(@Param("code") String code, @Param("excludeId") String excludeId);

    @Select("SELECT COUNT(*) FROM airway_segment s "
            + "JOIN airway a ON a.id = s.airway_id "
            + "WHERE (s.start_point_id = #{id} OR s.end_point_id = #{id}) "
            + "AND s.deleted = FALSE AND a.deleted = FALSE")
    int countActiveAirwayReferences(String id);

    @Insert("INSERT INTO navigation_point (id, code, name, point_type, longitude, latitude, elevation_m, "
            + "frequency_mhz, magnetic_variation_deg, description, source_point_type, coordinate_text, relevant_flag, "
            + "applicable_airports, pilot_flag, dti_flag, tfm_flag, status, source_type, source_reference, "
            + "revision, deleted, created_by, updated_by) VALUES "
            + "(#{id}, #{code}, #{name}, #{pointType}, #{longitude}, #{latitude}, #{elevationM}, "
            + "#{frequencyMhz}, #{magneticVariationDeg}, #{description}, #{sourcePointType}, #{coordinateText}, "
            + "#{relevantFlag}, #{applicableAirports}, #{pilotFlag}, #{dtiFlag}, #{tfmFlag}, #{status}, #{sourceType}, "
            + "#{sourceReference}, 0, FALSE, #{createdBy}, #{updatedBy})")
    int insert(NavPointRow row);

    @Update("UPDATE navigation_point SET code = #{code}, name = #{name}, point_type = #{pointType}, "
            + "longitude = #{longitude}, latitude = #{latitude}, elevation_m = #{elevationM}, "
            + "frequency_mhz = #{frequencyMhz}, magnetic_variation_deg = #{magneticVariationDeg}, "
            + "description = #{description}, source_point_type = #{sourcePointType}, coordinate_text = #{coordinateText}, "
            + "relevant_flag = #{relevantFlag}, applicable_airports = #{applicableAirports}, "
            + "pilot_flag = #{pilotFlag}, dti_flag = #{dtiFlag}, tfm_flag = #{tfmFlag}, "
            + "status = #{status}, source_reference = #{sourceReference}, "
            + "updated_by = #{updatedBy}, revision = revision + 1 "
            + "WHERE id = #{id} AND revision = #{revision} AND deleted = FALSE")
    int update(NavPointRow row);

    @Update("UPDATE navigation_point SET deleted = TRUE, revision = revision + 1 "
            + "WHERE id = #{id} AND revision = #{revision} AND deleted = FALSE")
    int markDeleted(@Param("id") String id, @Param("revision") int revision);

    @Update("UPDATE navigation_point SET status = #{status}, revision = revision + 1, updated_by = #{updatedBy} "
            + "WHERE id = #{id} AND revision = #{revision} AND deleted = FALSE")
    int updateStatus(@Param("id") String id, @Param("revision") int revision,
                     @Param("status") String status, @Param("updatedBy") String updatedBy);
}
