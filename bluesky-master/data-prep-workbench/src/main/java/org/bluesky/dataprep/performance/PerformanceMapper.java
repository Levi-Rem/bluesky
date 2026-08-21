package org.bluesky.dataprep.performance;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface PerformanceMapper {

    String COLS = "id, code, name, manufacturer, model_name, performance_source, engine_type, "
            + "wake_turbulence_category, maximum_takeoff_weight_kg, maximum_altitude_ft, maximum_mach, "
            + "default_bank_angle_deg, status, source_type, source_reference, revision, deleted, "
            + "created_at, created_by, updated_at, updated_by ";

    @Select("SELECT " + COLS + "FROM aircraft_type_performance WHERE deleted = FALSE ORDER BY code LIMIT #{size} OFFSET #{offset}")
    List<PerformanceRow> selectPage(@Param("offset") int offset, @Param("size") int size);

    @Select("SELECT COUNT(*) FROM aircraft_type_performance WHERE deleted = FALSE")
    long count();

    @Select("SELECT " + COLS + "FROM aircraft_type_performance WHERE id = #{id} AND deleted = FALSE")
    PerformanceRow findById(String id);

    @Select("SELECT COUNT(*) FROM aircraft_type_performance WHERE code = #{code} AND deleted = FALSE AND id <> #{excludeId}")
    int countByCode(@Param("code") String code, @Param("excludeId") String excludeId);

    @Insert("INSERT INTO aircraft_type_performance (id, code, name, manufacturer, model_name, "
            + "performance_source, engine_type, wake_turbulence_category, maximum_takeoff_weight_kg, "
            + "maximum_altitude_ft, maximum_mach, default_bank_angle_deg, status, source_type, "
            + "source_reference, revision, deleted, created_by, updated_by) VALUES "
            + "(#{id}, #{code}, #{name}, #{manufacturer}, #{modelName}, #{performanceSource}, #{engineType}, "
            + "#{wakeTurbulenceCategory}, #{maximumTakeoffWeightKg}, #{maximumAltitudeFt}, #{maximumMach}, "
            + "#{defaultBankAngleDeg}, #{status}, #{sourceType}, #{sourceReference}, 0, FALSE, "
            + "#{createdBy}, #{updatedBy})")
    int insert(PerformanceRow row);

    @Update("UPDATE aircraft_type_performance SET code = #{code}, name = #{name}, manufacturer = #{manufacturer}, "
            + "model_name = #{modelName}, performance_source = #{performanceSource}, engine_type = #{engineType}, "
            + "wake_turbulence_category = #{wakeTurbulenceCategory}, "
            + "maximum_takeoff_weight_kg = #{maximumTakeoffWeightKg}, maximum_altitude_ft = #{maximumAltitudeFt}, "
            + "maximum_mach = #{maximumMach}, default_bank_angle_deg = #{defaultBankAngleDeg}, "
            + "status = #{status}, source_reference = #{sourceReference}, updated_by = #{updatedBy}, "
            + "revision = revision + 1 WHERE id = #{id} AND revision = #{revision} AND deleted = FALSE")
    int update(PerformanceRow row);

    @Update("UPDATE aircraft_type_performance SET deleted = TRUE, revision = revision + 1 "
            + "WHERE id = #{id} AND revision = #{revision} AND deleted = FALSE")
    int markDeleted(@Param("id") String id, @Param("revision") int revision);

    @Update("UPDATE aircraft_type_performance SET status = #{status}, revision = revision + 1 "
            + "WHERE id = #{id} AND revision = #{revision} AND deleted = FALSE")
    int updateStatus(@Param("id") String id, @Param("revision") int revision, @Param("status") String status);
}
